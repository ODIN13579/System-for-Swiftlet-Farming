
import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

const house = { id: 17, active: true, name: 'Control House' }
const zone = { id: 33, houseId: house.id, active: true, name: 'Control Zone' }
const otherZone = { id: 34, houseId: house.id, active: true, name: 'Other Zone' }
const esp = { id: 51, zoneId: zone.id, deviceId: 'ESP-CONTROL', name: 'ESP Test', enabled: true, online: true, type: 'ESP32' }
const camera = { ...esp, id: 52, deviceId: 'CAM-TEST', name: 'Camera Test', type: 'CAMERA' }
const secondEsp = { ...esp, id: 54, deviceId: 'ESP-SECOND', name: 'ESP Second' }
const types = ['FAN', 'MIST', 'HEATER', 'AMPLIFIER']
const titles = ['Quạt thông gió', 'Máy phun sương', 'Thiết bị sưởi', 'Bộ khuếch đại âm thanh']
const list = (items: unknown[], totalPages = 1) => ({ items, page: { totalPages, totalElements: items.length } })

type Input = { action: 'SET_MODE' | 'SET_STATE'; targetMode?: string; targetState?: boolean }
async function fixture(page: Page, opts: { online?: boolean; canControl?: boolean; fault?: number } = {}) {
  let mode = 'AUTO'
  let ack = false
  let apply = false
  const on = new Map<number, boolean>()
  const writes: { actuator: number; input: Input }[] = []
  const polls: string[] = []
  const deviceSockets: WebSocketRoute[] = []
  await page.routeWebSocket('**/ws/**', socket => {
    if (socket.url().endsWith('/ws/devices/51')) deviceSockets.push(socket)
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    if (method === 'OPTIONS') return route.fulfill({ status: 204 })
    if (path.endsWith('/auth/login')) return route.fulfill({ json: { accessToken: 'fixture-token' } })
    if (path.endsWith('/me')) return route.fulfill({ json: { role: 'OWNER', email: 'qa@example.test' } })
    if (path.endsWith('/houses')) return route.fulfill({ json: list([house]) })
    if (path.endsWith('/zones')) return route.fulfill({ json: list([zone, otherZone]) })
    if (path === '/api/v1/zones/33/devices') {
      const devices = url.searchParams.get('page') === '0'
        ? [{ ...esp, online: opts.online ?? true }, camera] : [secondEsp]
      return route.fulfill({ json: list(devices, 2) })
    }
    if (path.endsWith('/actuators')) {
      if (ack && apply && writes.length) {
        const entry = writes.at(-1)!
        if (entry.input.targetMode) mode = entry.input.targetMode
        if (entry.input.targetState !== undefined) on.set(entry.actuator, entry.input.targetState)
      }
      const id = Number(path.split('/').at(-2))
      return route.fulfill({ json: list(types.map((kind, index) => ({
        id: id * 10 + index, deviceId: id, kind, mode, on: on.get(id * 10 + index) ?? false,
        canControl: opts.canControl ?? true, safetyOverride: false,
        stateFresh: true, stateReceivedAt: new Date().toISOString(),
      }))) })
    }
    if (method === 'POST' && path.endsWith('/commands')) {
      const actuator = Number(path.split('/').at(-2))
      writes.push({ actuator, input: route.request().postDataJSON() })
      if (opts.fault) return route.fulfill({ status: opts.fault, json: { code: 'SAFETY_OVERRIDE' } })
      return route.fulfill({ status: 202, json: { commandId: 'test-' + writes.length, status: 'PENDING' } })
    }
    if (path.includes('/commands/test-')) {
      polls.push(path)
      if (ack && apply) {
        const entry = writes.at(-1)!
        if (entry.input.targetMode) mode = entry.input.targetMode
        if (entry.input.targetState !== undefined) on.set(entry.actuator, entry.input.targetState)
      }
      return route.fulfill({ json: { status: ack ? 'ACKNOWLEDGED' : 'SENT' } })
    }
    if (path.endsWith('/latest')) return route.fulfill({ status: 404 })
    return route.fulfill({ json: list([]) })
  })
  await page.goto('/')
  await page.getByLabel('Email').fill('qa@example.test')
  await page.locator('#login-password').fill('fixture-password')
  await page.locator('button[type=submit]').click()
  return { writes, polls, deviceSockets, acknowledge(persist: boolean) { ack = true; apply = persist } }
}

test('shared selection and four outputs require command ACK plus stored state', async ({ page }) => {
  const qa = await fixture(page)
  const dashboardSelect = page.getByLabel('ESP32', { exact: true })
  await dashboardSelect.selectOption('54')
  await page.getByRole('button', { name: /Môi trường & Thiết bị/ }).click()
  const panel = page.getByRole('region', { name: 'Điều khiển thiết bị' })
  await expect(panel.getByRole('heading')).toContainText('ESP Second')
  await expect(panel.getByRole('button', { name: 'Bật ' + titles[0] })).toBeDisabled()
  const manual = panel.getByRole('button', { name: 'MANUAL' })
  await manual.click()
  await expect.poll(() => qa.polls.length).toBeGreaterThan(0)
  await expect(manual).toBeDisabled()
  await expect(panel.getByRole('button', { name: 'AUTO' })).toHaveAttribute('aria-pressed', 'true')
  qa.acknowledge(false)
  await expect(panel.getByText('Đã nhận ACK. Đang đối chiếu trạng thái ESP32…')).toBeVisible()
  await expect(manual).toHaveAttribute('aria-pressed', 'false')
  qa.acknowledge(true)
  // The fixture applies the state independently of the ACK endpoint.
  await expect(manual).toHaveAttribute('aria-pressed', 'true', { timeout: 7000 })
  for (const title of titles) {
    await panel.getByRole('button', { name: 'Bật ' + title }).click()
    await expect(panel.getByRole('button', { name: 'Tắt ' + title })).toBeEnabled()
    await panel.getByRole('button', { name: 'Tắt ' + title }).click()
    await expect(panel.getByRole('button', { name: 'Bật ' + title })).toBeEnabled()
  }
  await panel.getByRole('button', { name: 'AUTO' }).click()
  await expect(panel.getByRole('button', { name: 'AUTO' })).toHaveAttribute('aria-pressed', 'true')
  expect(qa.writes).toHaveLength(10)
  expect(qa.writes.slice(1, 9).map(entry => entry.actuator)).toEqual([540, 540, 541, 541, 542, 542, 543, 543])
})

for (const options of [{ online: false }, { canControl: false }, { fault: 403 }, { fault: 409 }, { fault: 503 }]) {
  test('blocks or explains an unavailable control request ' + JSON.stringify(options), async ({ page }) => {
    const qa = await fixture(page, options)
    await page.getByRole('button', { name: /Môi trường & Thiết bị/ }).click()
    const panel = page.getByRole('region', { name: 'Điều khiển thiết bị' })
    const manual = panel.getByRole('button', { name: 'MANUAL' })
    if (options.fault) {
      await manual.click()
      await expect(panel.getByRole('alert')).toContainText(
        options.fault === 403 ? 'không có quyền' : options.fault === 409 ? 'ngăn tắt quạt' : 'chưa sẵn sàng',
      )
    } else {
      await expect(panel.getByRole('button', { name: 'AUTO' })).toHaveAttribute('aria-pressed', 'true')
      await expect(manual).toBeDisabled()
      expect(qa.writes).toHaveLength(0)
    }
  })
}

test('changing Zone aborts a pending status loop', async ({ page }) => {
  const qa = await fixture(page)
  await page.getByRole('button', { name: /Môi trường & Thiết bị/ }).click()
  await page.getByRole('button', { name: 'MANUAL' }).click()
  await expect.poll(() => qa.polls.length).toBeGreaterThan(0)
  await page.getByLabel('Khu vực').selectOption('34')
  await expect(page.getByRole('region', { name: 'Điều khiển thiết bị' })).toHaveCount(0)
  const stoppedAt = qa.polls.length
  await page.waitForTimeout(1100)
  expect(qa.polls).toHaveLength(stoppedAt)
})

test('Dashboard reconnects and ignores delayed and foreign ESP32 telemetry', async ({ page }) => {
  const qa = await fixture(page)
  await expect.poll(() => qa.deviceSockets.length).toBeGreaterThan(0)
  const first = qa.deviceSockets.at(-1)!
  const priorCount = qa.deviceSockets.length
  const publish = (socket: WebSocketRoute, deviceId: number, timestamp: string, temperature: number) =>
    socket.send(JSON.stringify({ type: 'telemetry.updated', data: { deviceId, timestamp, temperature } }))
  const metric = page.locator('.metric-card').filter({ hasText: 'Nhiệt độ' })
  publish(first, 51, '2026-11-27T06:00:02Z', 31)
  await expect(metric).toContainText('31.0')
  publish(first, 52, '2026-11-27T06:00:03Z', 70)
  publish(first, 51, '2026-11-27T06:00:01Z', 60)
  first.close({ code: 1011 })
  await expect.poll(() => qa.deviceSockets.length).toBeGreaterThan(priorCount)
  await expect(metric).toContainText('31.0')
  publish(qa.deviceSockets.at(-1)!, 51, '2026-11-27T06:00:04Z', 32)
  await expect(metric).toContainText('32.0')
  await page.getByLabel('ESP32', { exact: true }).selectOption('54')
  await expect(metric).not.toContainText('32.0')
})
