# Build 3 frontend integration verification

QA run: 2026-11-27. Main base: 2dff3c96993507ca6144d9c685748a95056a32d8. Frontend source: 876d7244c002f1454e08adba1893e2a59f22b7d4. The B3-20 statistics controller, contract and tests were imported from the local workspace into the integration worktree.

- Maven verify: 57 tests, zero failures, errors and skips. Dedicated PostgreSQL 17 (55436) and Mosquitto (51886). Reports tests compare PostgreSQL samples, aggregate JSON and UTC+7 CSV across midnight.
- Edge Playwright: 7 passed. Six tests use HTTP/WebSocket fixtures. One runs against the real Backend (18087) and a dedicated browser database. Responsive checks cover Dashboard, Alerts, Reports and Admin at 360, 768, 1024 and 1440 px.
- Real Alert sample ID 2: Backend createdAt 2026-11-27T04:36:17.680819Z, React DOM observer 2026-11-27T04:36:17.745Z, elapsed 65 ms. The same Alert ID was verified through WebSocket, REST and the rendered row. The test also checked authenticated snapshot display and HTTP 401 with an invalid token. This was one sample on a shared QA host clock.
- Native desktop notifications and 3–5 person UX sessions remain unverified. B3-56 Live Video is deferred.

For fixture tests in frontend, run npm ci, npx playwright install chromium, and npm test. To include the real test, configure BUILD3_E2E_BACKEND, BUILD3_E2E_PASSWORD and BUILD3_E2E_AI_KEY for an isolated Backend started with the local seed profile. Set VITE_API_BASE_URL and VITE_WS_BASE_URL to the same Backend host and allow the frontend origin http://localhost:15175 through FRONTEND_ORIGINS. The test adds a QA Zone, Camera and AI event per run. Set PLAYWRIGHT_CHANNEL=msedge to use installed Edge.
