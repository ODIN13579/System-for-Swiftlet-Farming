import { defineConfig } from '@playwright/test'

const port = Number(process.env.PLAYWRIGHT_PORT || (process.env.BUILD3_E2E_BACKEND ? 15175 : 15176))

export default defineConfig({
  testDir: './tests',
  use: { baseURL: 'http://localhost:' + port, timezoneId: 'UTC', channel: process.env.PLAYWRIGHT_CHANNEL || undefined },
  webServer: process.env.PLAYWRIGHT_EXTERNAL_SERVER ? undefined : {
    command: 'node node_modules/vite/bin/vite.js --host localhost --strictPort --port ' + port,
    url: 'http://localhost:' + port,
  },
})
