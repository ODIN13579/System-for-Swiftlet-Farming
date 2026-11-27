
import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'

const backend = process.env.BUILD3_E2E_BACKEND
test.skip(!backend, 'Requires an isolated backend with the local QA seed')

test('Backend predator Alert reaches React DOM and authenticated snapshot', async ({ page, request }) => {
  const password = process.env.BUILD3_E2E_PASSWORD!
  const key = process.env.BUILD3_E2E_AI_KEY!
  const email = process.env.BUILD3_E2E_EMAIL || 'local@example.test'
  const auth = await request.post(backend + '/api/v1/auth/login', { data: { email, password } })
  expect(auth.ok()).toBeTruthy()
  const headers = { Authorization: 'Bearer ' + (await auth.json()).accessToken }
  const houses = await request.get(backend + '/api/v1/houses', { headers })
  const house = (await houses.json()).items.find((item: {name: string}) => item.name === 'Local test house')
  const fixture = randomUUID()
  const zoneResponse = await request.post(backend + '/api/v1/houses/' + house.id + '/zones', {
    headers, data: { name: 'QA Browser ' + fixture },
  })
  expect(zoneResponse.status(), await zoneResponse.text()).toBe(201)
  const zone = await zoneResponse.json()
  const cameraResponse = await request.post(backend + '/api/v1/zones/' + zone.id + '/devices', {
    headers, data: { deviceId: 'CAM-' + fixture, name: 'QA Camera', type: 'CAMERA', enabled: true },
  })
  expect(cameraResponse.status(), await cameraResponse.text()).toBe(201)
  const camera = await cameraResponse.json()
  const serviceHeaders = { 'X-Service-Key': key }

  await page.goto('/')
  const socketReady = page.waitForEvent('websocket', socket => socket.url().endsWith('/ws/houses/' + house.id))
  await page.getByLabel('Email').fill(email)
  await page.locator('#login-password').fill(password)
  await page.locator('button[type=submit]').click()
  const socket = await socketReady
  await page.getByRole('button', { name: /Cảnh báo & AI Events/ }).click()
  await expect(page.getByText('Realtime đang kết nối')).toBeVisible()

  const upload = await request.post(backend + '/api/ai/snapshots', {
    headers: serviceHeaders,
    multipart: { camera_id: String(camera.id), file: {
      name: 'qa.png', mimeType: 'image/png',
      buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+WjFcAAAAASUVORK5CYII=', 'base64'),
    } },
  })
  expect(upload.status(), await upload.text()).toBe(201)
  const snapshot = (await upload.json()).snapshot_id
  await page.evaluate(() => {
    const observed = new Map<string, number>()
    new MutationObserver(() => {
      for (const row of document.querySelectorAll<HTMLElement>('[data-alert-id]')) {
        const id = row.dataset.alertId!
        if (!observed.has(id)) observed.set(id, Date.now())
      }
    }).observe(document.body, { childList: true, subtree: true })
    Object.assign(window, { observedAlertTimes: observed })
  })
  const websocketAlert = socket.waitForEvent('framereceived', frame => {
    const message = JSON.parse(String(frame.payload))
    return message.type === 'alert.created' && message.data.zoneId === zone.id
  })
  const published = await request.post(backend + '/api/ai/events', {
    headers: serviceHeaders,
    data: { event_id: randomUUID(), camera_id: camera.id, snapshot_id: snapshot, species: 'OWL', confidence: 0.98 },
  })
  expect(published.status(), await published.text()).toBe(201)
  const { alert_id: id } = await published.json()
  const row = page.locator('[data-alert-id="' + id + '"]')
  await expect(row).toBeVisible({ timeout: 5000 })
  expect(JSON.parse(String((await websocketAlert).payload)).data.id).toBe(id)
  const dom = await page.evaluate(id =>
    (window as unknown as {observedAlertTimes: Map<string, number>}).observedAlertTimes.get(String(id))!, id,
  )
  const detail = await request.get(backend + '/api/v1/alerts/' + id, { headers })
  expect(detail.ok()).toBeTruthy()
  const created = (await detail.json()).createdAt as string
  const elapsed = dom - Date.parse(created)
  console.log(JSON.stringify({ alertId: id, createdAt: created, domAt: new Date(dom).toISOString(), elapsedMs: elapsed }))
  expect(elapsed).toBeGreaterThanOrEqual(0)
  expect(elapsed).toBeLessThanOrEqual(5000)
  await row.getByRole('button').click()
  await expect(page.getByRole('dialog').locator('img')).toBeVisible()
  const denied = await page.request.get(backend + '/api/snapshots/' + snapshot, { headers: { Authorization: 'Bearer invalid-token' } })
  expect(denied.status()).toBe(401)
})
