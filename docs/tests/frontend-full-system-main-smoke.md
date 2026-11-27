# SwiftletCare Build 3 full-system smoke

Baseline `origin/main` was `64b8991b2c83b962790f7cbba1f2193a268a5858` after fetch. Fix branch `fix/frontend-build3-full-integration` is in `%TEMP%/swiftletcare-fe-full`. The original workspace is on `AI-b3` with existing firmware, Backend and generated-file changes. All edits below are in the separate worktree.

## Frontend audit

| Files | Function | Finding and priority | Change and evidence |
|---|---|---|---|
| `App.tsx`, `DeviceScope.tsx`, `Devices.tsx` | Device selection and status | P0 control was disconnected. P1 Devices depended on a fixed Zone and only requested one page. | Shared authorized House/Zone/ESP32 selection, page traversal, refresh and Online/Offline from Backend. Edge fixture verifies selection persists across pages and picks an ESP32 from the second page. |
| `FanControl.tsx`, `services/api.ts`, `App.css` | AUTO/MANUAL and four outputs | P0 legacy panel lacked permission, freshness and post-ACK state confirmation. | Controls gate on READ response capability, ESP32 availability, state freshness and safety status. POST 202 opens a cancellable command-status loop. Success requires ACKNOWLEDGED and a newer reported state matching the target. Edge verifies both modes, all four outputs, pending lock, cancellation, Offline, 403, 409 and 503. |
| `Dashboard.tsx`, `services/websocket.ts`, `DashboardAlerts.tsx` | Dashboard realtime | P1 only the first sensor was shown. A late read could overwrite WS data and disconnect had no retry. | Shared scope, paced REST reads, timestamp and source-ID filtering, reconnect backoff and per-device cleanup. Device status and WS connection are tracked independently. |
| `Login.tsx`, `App.tsx`, `services/api.ts`, `Profile.tsx` | Session | P1 profile failures after JWT login only reached console. WS fallback could use a different host than REST. | Login awaits profile and reports failure inline, logout clears local state, 401 invalidates current session and default WS URL derives from the REST URL. Backend uses SC_WS with SameSite=Strict and checks authorization. |
| `AlertSession.tsx`, `Alerts.tsx`, `services/browserNotifications.ts` | Alert/notification | P2 native notification and live multi-House E2E remain unverified. | Existing single House subscription, reconnect, link, authenticated snapshot and scope checks retained. Fixture covers Dashboard notifications, unauthorized event filtering, preference/permission, deduplication, House switch and reconnection of the selected scope. |
| `Reports.tsx`, `AiCounts.tsx` | Reports and bird counts | Verified environment reports are scoped to Zone; selected device scopes paginated raw telemetry only. | Kept Backend aggregates and CSV. UTC+7 query parity checked in Edge, database values checked by Maven. AI widget filters HOURLY for chart totals. |
| `Houses.tsx`, `Zones.tsx`, `ZoneConfig.tsx`, `ZoneConfigPanel.tsx`, `hooks/useDevices.ts` | Management | P2 brittle substring check for version conflict. Standalone hook/panel are unused by normal navigation. | ZoneConfig uses HTTP 409 status, retains thresholds, hysteresis and server syncStatus. CRUD, roles and config ACK covered by Backend regression; complete browser role/CRUD matrix pending. |
| `AdminUsers.tsx`, `Profile.tsx` | Accounts | Admin view uses global ADMIN gate. Backend applies House roles to resource permissions. | Existing account and password flows retained. Backend regression covers account lifecycle and token revocation. |
| `App.css`, `index.css`, `main.tsx`, assets | Layout/bootstrap | P2 mobile initial sidebar could cover content. | Small screens start with collapsed sidebar. New actuator controls wrap and have 44 px targets and focus indicators. Baseline regression measures 360/768/1024/1440 px. |

Backend additions expose existing actuator state timestamp, freshness, safety override and the existing WRITE permission decision on the actuator read response. The command endpoints, auth policy, database schema and firmware protocol are unchanged. `ControlCommandsIntegrationTest.java` asserts authorization metadata and distinguishes defaults from firmware state. The optional `backend/scripts/bootstrap-demo-actuators.sql` inserts missing actuator registrations for the active ESP32-LOCAL in a database named swiftletcare_demo. No account, state or other device is overwritten, and the script is never invoked on startup.

## Demo inspection and outcomes

Read-only inspection of the existing demo found the Backend healthy at 18085, Docker PostgreSQL listening at 55437 and MQTT listening at 51887. The registered ESP32-LOCAL had House 1, Zone 1, no Heartbeat timestamp, was offline and had no actuator rows. One historical telemetry row existed with received_at 2026-11-27T05:57:39.963798Z. Its source was not established. A passive 8-second MQTT subscription on the demo Heartbeat and telemetry ACK topics timed out. No demo or existing QA container, database or soak log was modified.

- PASS: npm ci, npm run lint, npm run build and git diff --check. Lint exited 0 with 17 warnings, chiefly React effect and Fast Refresh rules. Vite reports a 743.20 kB minified chunk, above its 500 kB warning threshold. Build has no TypeScript errors.
- PASS: npm test on Edge, 15 fixture tests passed. The optional genuine Backend test was skipped in that run and passed in its separate run below.
- PASS: Maven verify on PostgreSQL 55438 and Mosquitto 51888, 57 tests with zero failures/errors/skips. REST/MQTT integration uses a test MQTT client for Heartbeat, commands, valid ACK and state, with 403 and FAN safety checks.
- PASS: Explicit bootstrap test twice against a separate swiftletcare_demo database in the new regression container. Exactly one registration per actuator after both runs.
- PASS: PlatformIO firmware build from a temporary copy with demo MQTT/identity. SHA-256 D5B3F5EF9678F474D8BB76B0BF07ECD09A89995DF0BCCC940188B77016A334E5.
- PASS: Real Edge E2E authenticated with the integrated Backend at 18089 using a new browser database. Alert 1 reached React DOM in 139 ms (createdAt 2026-11-27T06:16:33.529922Z, DOM 2026-11-27T06:16:33.668Z). Same ID verified over WS, REST and DOM. The snapshot was displayed through the authenticated API and an invalid token returned HTTP 401.
- PARTIAL: Browser and MQTT layers have independent evidence. Full UI-to-Wokwi round trip and safety behavior in the Wokwi simulator are NOT TESTED. No new Wokwi Heartbeat, Telemetry, STORED or command_ack was captured for this run.
- NOT TESTED: Native desktop notification, complete management browser authorization matrix and aggregate NFR. One Alert latency sample cannot validate the full NFR.

## Windows PowerShell runbook

Use the worktree below. Existing demo FE/BE processes already occupied 5173 and 18085 during audit. Reuse the demo DB and broker only if they are the intended demo services. Before launching a new Backend or Frontend on those ports, stop your corresponding demo processes from their own terminals or explicitly choose unused ports and update origins. Use localhost consistently in browser URLs.

Terminal 1, demo bootstrap and Backend. Run bootstrap after migrations are applied and ESP32-LOCAL is registered. The existing demo DB was inspected and met those prerequisites.

```powershell
$w = Join-Path $env:TEMP 'swiftletcare-fe-full'
Set-Location $w
docker cp "$w/backend/scripts/bootstrap-demo-actuators.sql" swiftletcare-demo-db:/tmp/bootstrap-demo-actuators.sql
if ($LASTEXITCODE) { throw 'Copy failed' }
docker exec swiftletcare-demo-db sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/bootstrap-demo-actuators.sql'
if ($LASTEXITCODE) { throw 'Bootstrap failed' }
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55437/swiftletcare_demo'
$env:DB_USERNAME = docker exec swiftletcare-demo-db printenv POSTGRES_USER
$env:DB_PASSWORD = docker exec swiftletcare-demo-db printenv POSTGRES_PASSWORD
$env:SERVER_PORT = '18085'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:FRONTEND_ORIGINS = 'http://localhost:5173'
$env:MQTT_ENABLED = 'true'
$env:MQTT_URL = 'tcp://127.0.0.1:51887'
$env:MQTT_CLIENT_ID = 'swiftletcare-demo-full-fe'
$env:CONTROL_COMMANDS_ENABLED = 'true'
foreach ($name in 'JWT_SECRET','AI_SERVICE_KEY') {
  if (![Environment]::GetEnvironmentVariable($name)) {
    $value = Read-Host "Enter existing demo $name" -AsSecureString
    [Environment]::SetEnvironmentVariable($name, [Net.NetworkCredential]::new('', $value).Password)
  }
}
Set-Location "$w/backend"
$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (!$maven) {
  $maven = Get-ChildItem "$env:TEMP/ackfix-maven*" -Recurse -Filter mvn.cmd |
    Select-Object -First 1
}
if (!$maven) { throw 'Maven executable not found' }
$mavenPath = if ($maven.Source) { $maven.Source } else { $maven.FullName }
& $mavenPath -DskipTests package
if ($LASTEXITCODE) { throw 'Backend build failed' }
java -jar target/swiftletcare-backend-0.0.1-SNAPSHOT.jar
```

Terminal 2, Frontend. Sign in with the existing demo account.

```powershell
Set-Location "$env:TEMP/swiftletcare-fe-full/frontend"
$env:VITE_API_BASE_URL = 'http://localhost:18085'
$env:VITE_WS_BASE_URL = 'ws://localhost:18085'
npm.cmd ci
if ($LASTEXITCODE) { throw 'npm ci failed' }
npm.cmd run dev -- --host localhost --port 5173 --strictPort
```

Terminal 3, read-only MQTT observer. Only observe the dedicated demo broker.

```powershell
$topic = 'swiftletcare/1/1/ESP32-LOCAL'
$mqtt = Join-Path $env:ProgramFiles 'Mosquitto/mosquitto_sub.exe'
& $mqtt -h 127.0.0.1 -p 51887 -v -t "$topic/heartbeat" -t "$topic/telemetry" -t "$topic/telemetry_ack" -t "$topic/command" -t "$topic/command_ack" -t "$topic/state"
```

Open the temporary firmware project `%TEMP%/swiftletcare-wokwi-demo-b9d950f5cd2b41cd95f1194482ce842f` in VS Code and launch Wokwi Simulator. This copy routes to host.wokwi.internal:51887 and uses the House/Zone/device confirmed above. If those IDs have changed, first read the IDs from the demo DB and adjust every MQTT topic prefix and device constant in a fresh **copy** of the firmware. Verify the firmware.bin SHA-256 before starting.

Confirm Heartbeat, a new Telemetry event_id and its STORED ACK in the subscriber, then check that Dashboard updates and shows Online. In Devices, select the same ESP32, switch AUTO to MANUAL, exercise each output where allowed, then switch back to AUTO. For each command, match command_id with command_ack, check the reported state, and verify that the UI confirms both. FAN OFF during safety override must stay disabled or be rejected. Record any REJECTED/FAILED/EXPIRED status, and keep the Wokwi gate NOT TESTED until these observations are available.
