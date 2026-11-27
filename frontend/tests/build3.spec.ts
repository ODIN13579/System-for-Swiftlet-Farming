
import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

const house = { id: 1, name: 'QA House', active: true }
const zone = { id: 2, houseId: 1, name: 'QA Zone', active: true }
const alert = { id: 42, houseId: 1, zoneId: 2, level: 'CRITICAL', type: 'TEMP_HIGH', status: 'NEW', message: 'QA alert 42', createdAt: '2026-11-27T00:00:00Z' }
const list = (items: unknown[]) => ({ items, page: { totalPages: 1, totalElements: items.length } })

async function setup(page: Page, permission = 'granted', enabled = true, role = 'OWNER', multipleHouses = false) {
  let alerts: typeof alert[] = []
  const sockets: ((message: unknown) => void)[] = []
  await page.addInitScript(({ permission, enabled }) => {
    localStorage.setItem('swiftletcare-notifications-enabled', String(enabled))
    const notices: Array<{ onclick?: () => void }> = []
    Object.assign(window, { notices, focus: () => {}, Notification: class {
      static permission = permission
      constructor() { notices.push(this) }
      close() {}
    } })
  }, { permission, enabled })
  const houseSockets: WebSocketRoute[] = []
  await page.routeWebSocket('**/ws/houses/*', socket => {
    houseSockets.push(socket)
    sockets.push(message => socket.send(JSON.stringify(message)))
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204 })
    if (path.endsWith('/login')) return route.fulfill({ json: { accessToken: 'browser-test-token' } })
    if (path.endsWith('/logout')) return route.fulfill({ status: 204 })
    if (path.endsWith('/me')) return route.fulfill({ json: { id: 1, role, email: 'qa@example.test', active: true } })
    if (path.endsWith('/houses')) return route.fulfill({ json: list(multipleHouses ? [house, { ...house, id: 3, name: 'Second House' }] : [house]) })
    if (path.endsWith('/zones')) return route.fulfill({ json: list([zone]) })
    if (path.endsWith('/alerts')) {
      const status = url.searchParams.get('status')
      return route.fulfill({ json: list(alerts.filter(entry => !status || entry.status === status)) })
    }
    if (path.endsWith('/alerts/42')) return route.fulfill({ json: alert })
    if (path.endsWith('/alerts/42/history')) return route.fulfill({ json: [] })
    if (path.endsWith('/environment')) return route.fulfill({ json: { points: [
      { timestamp: '2026-11-27T00:00:00+07:00', sampleCount: 2, avgTemperature: 25, minTemperature: 20, maxTemperature: 30 },
    ] } })
    if (path.endsWith('/environment.csv')) return route.fulfill({
      contentType: 'text/csv', body: 'timestamp_utc_plus_7,avg_temperature_celsius\n2026-11-27T00:00:00+07:00,25\n',
    })
    return route.fulfill({ json: list([]) })
  })
  await page.goto('/')
  await page.getByLabel('Email').fill('qa@example.test')
  await page.locator('#login-password').fill('test-password')
  await page.locator('button[type=submit]').click()
  await expect.poll(() => sockets.length).toBe(1)
  return {
    sockets,
    houseSockets,
    publish(houseId = 1) {
      if (houseId === 1) alerts = [alert]
      sockets.at(-1)?.({ type: 'alert.created', data: { ...alert, houseId } })
    },
  }
}

const noticeCount = (page: Page) => page.evaluate(() =>
  (window as unknown as { notices: unknown[] }).notices.length,
)

test('Dashboard notification, House filter, deduplication, deep link and logout', async ({ page }) => {
  const qa = await setup(page)
  await expect(page.getByRole('button', { name: /Quản lý tài khoản/ })).toHaveCount(0)
  qa.publish(99)
  qa.publish()
  qa.publish()
  await expect.poll(() => noticeCount(page)).toBe(1)
  await expect(page.locator('.alert-nav-count')).toHaveText('1')
  await page.evaluate(() =>
    (window as unknown as { notices: { onclick: () => void }[] }).notices[0].onclick(),
  )
  await expect(page).toHaveURL(/\?alertId=42$/)
  await expect(page.locator('[data-alert-id="42"]')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('QA alert 42')
  expect(qa.sockets).toHaveLength(1)
  await page.getByRole('dialog').locator('.alert-modal-close').click()
  await page.getByRole('button', { name: 'Đăng xuất' }).click()
  await expect(page.locator('#login-password')).toBeVisible()
})

for (const [permission, enabled] of [['denied', true], ['granted', false]] as const) {
  test('updates alerts without notification when ' + permission + '/' + enabled, async ({ page }) => {
    const qa = await setup(page, permission, enabled)
    qa.publish()
    await expect(page.locator('.alert-nav-count')).toHaveText('1')
    expect(await noticeCount(page)).toBe(0)
  })
}

test('report and CSV share a UTC+7 aggregate query with a UTC browser', async ({ page }) => {
  await setup(page)
  await page.getByRole('button', { name: /Thống kê & Báo cáo/ }).click()
  const response = page.waitForResponse(url => url.url().includes('/reports/environment?'))
  await page.locator('select').filter({ has: page.locator('option[value="1"]', { hasText: 'QA House' }) }).selectOption('1')
  await page.locator('select').filter({ has: page.locator('option[value="2"]', { hasText: 'QA Zone' }) }).selectOption('2')
  const aggregate = new URL((await response).url())
  expect(aggregate.searchParams.get('from')).toMatch(/T00:00:00\+07:00$/)
  expect(aggregate.searchParams.get('granularity')).toBe('HOURLY')
  const csv = page.waitForResponse(url => url.url().includes('/reports/environment.csv?'))
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Xuất CSV' }).click()
  expect(new URL((await csv).url()).search).toBe(aggregate.search)
  expect((await download).suggestedFilename()).toBe('swiftletcare-environment.csv')
})

test('Dashboard, Alerts, Reports and Admin fit the supported viewport widths', async ({ page }) => {
  await setup(page, 'denied', false, 'ADMIN')
  for (const width of [360, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    for (const title of ['Tổng quan', 'Cảnh báo & AI Events', 'Thống kê & Báo cáo', 'Quản lý tài khoản']) {
      await page.locator('nav button').filter({ has: page.getByText(title, { exact: true }) }).click()
      const dimensions = await page.evaluate(() => ({
        documentWidth: document.documentElement.scrollWidth,
        viewportWidth: window.innerWidth,
      }))
      expect(dimensions.documentWidth, title + ' at ' + width).toBeLessThanOrEqual(dimensions.viewportWidth)
    }
  }
})

test('401 on authenticated data routes returns the user to login', async ({ page }) => {
  await setup(page)
  await page.route('**/api/v1/houses?*', route => route.fulfill({ status: 401 }))
  await page.getByRole('button', { name: /Thống kê & Báo cáo/ }).click()
  await expect(page.locator('#login-password')).toBeVisible()
})

test('switching House closes the old alert socket and reconnects only the new scope', async ({ page }) => {
  const qa = await setup(page, 'granted', true, 'OWNER', true)
  const previous = qa.houseSockets[0]
  let previousClosed = false
  previous.onClose(() => { previousClosed = true })
  await page.getByLabel('Nhà yến', { exact: true }).selectOption('3')
  await expect.poll(() => previousClosed).toBe(true)
  await expect.poll(() => qa.houseSockets.length).toBe(2)
  const current = qa.houseSockets[1]
  expect(current.url()).toMatch(/\/3$/)
  current.send(JSON.stringify({ type: 'alert.created', data: alert }))
  current.send(JSON.stringify({ type: 'alert.created', data: { ...alert, id: 45, houseId: 3 } }))
  await expect.poll(() => noticeCount(page)).toBe(1)
  current.close({ code: 1011 })
  await expect.poll(() => qa.houseSockets.length).toBe(3)
  expect(qa.houseSockets[2].url()).toMatch(/\/3$/)
  qa.houseSockets[2].send(JSON.stringify({ type: 'alert.created', data: { ...alert, id: 46, houseId: 3 } }))
  await expect.poll(() => noticeCount(page)).toBe(2)
})
