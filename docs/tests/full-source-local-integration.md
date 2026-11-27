# SwiftletCare Build 3 integrated local source

## Git and recovery

- Repository: D:/New_folder/System-for-Swiftlet-Farming.
- Original branch AI-b3 at 7e0006433400c22df1eda3445a2008f9205ba97e. Integration branch integration/full-system-local starts at origin/main 64b8991b2c83b962790f7cbba1f2193a268a5858.
- External backup: %TEMP%/swiftletcare-source-backup-20261127-135125. It contains the original status and SHA, binary-capable staged and unstaged patches, copies of changed/untracked and firmware source files, local settings, and a verified PostgreSQL demo archive. Treat it as private.
- Retained stash: 93771c8ead7ed26b1ee82589847f650e00922947, based on the original AI-b3 SHA. Untracked-files parent: 66843325acfa80e6db51d9d4a6d687660a67a86c. Do not apply the whole stash onto integration/full-system-local because it would mix original build output and source with integrated versions.
- FE and control API changes from the worktree were committed as ffdf6470c3e13b38261b2139e5e61652c30e5932 and fast-forwarded into the original directory. See frontend-full-system-main-smoke.md for its file-level audit.
- Integrated selected AI-b3 source changes for camera heartbeat, snapshot cleanup, House membership, alert processing and reporting audit, with V10/V11 and associated tests. Integrated the local Telemetry ACK publisher and tests. Statistics API and tests were already equivalent on main, with additional actuator metadata documented in OpenAPI.
- Preserved firmware SENSOR_RECOVERED behavior and soak tools/documents. The original esp32dev environment retains its MQTT identity and default PlatformIO build. firmware/wokwi.soak.toml retains the original serial/debug ports and refers to the esp32dev binary. The standard firmware/wokwi.toml uses esp32demo and does not claim the soak ports.
- Original generated targets, debug logs, QA evidence, unrelated notes and local VS Code changes remain recoverable from the backup/stash. Existing ignored settings and prior firmware build folders were retained. Source branch changes exclude temporary Mosquitto test config, secrets, binaries and logs. No GitHub push or merge was performed.

## Run from source root

Run these PowerShell commands in terminals rooted at D:/New_folder/System-for-Swiftlet-Farming. Docker Desktop must be running. The existing demo containers are swiftletcare-demo-db on 55437 and swiftletcare-demo-mqtt on 51887. Existing QA and Windows MQTT services stay independent.

Terminal 1, Backend. If a demo instance is already running, reuse it or stop it in its terminal before launching another instance.

```powershell
Set-Location 'D:/New_folder/System-for-Swiftlet-Farming'
$maven = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
if (!$maven) {
    $maven = Get-ChildItem "$env:TEMP/ackfix-maven*" -Recurse -Filter mvn.cmd |
        Select-Object -First 1 -ExpandProperty FullName
}
if (!$maven) { throw 'Set the path to your Maven installation.' }
& ./scripts/start-local-demo-backend.ps1 -MavenPath $maven
```

The launcher reads JWT_SECRET and AI_SERVICE_KEY from the current environment or ignored .env. It verifies demo container mappings and sets the DB/MQTT endpoints and CONTROL_COMMANDS_ENABLED=true. It keeps the existing demo password. An empty, already migrated demo schema requires LOCAL_ADMIN_PASSWORD for the initial profile seed.

Terminal 2, bootstrap once readiness is UP. The bootstrap is idempotent and asserts one row per actuator.

```powershell
curl.exe -fsS http://localhost:18085/actuator/health/readiness
& ./scripts/bootstrap-local-demo-actuators.ps1
```

Terminal 3, Frontend. Use npm ci for the initial install or after package-lock.json changes, with the existing Vite process stopped.

```powershell
npm.cmd --prefix frontend ci
& ./scripts/start-local-demo-frontend.ps1
```

Open http://localhost:5173 and sign in with the existing demo account. The launcher uses http://localhost:18085 and ws://localhost:18085 so the SC_WS cookie can be reused for the WebSocket handshake.

Terminal 4, firmware and passive MQTT observation.

```powershell
& ./scripts/build-wokwi-demo.ps1
$topic = 'swiftletcare/1/1/ESP32-LOCAL' # Replace with the exact prefix printed by the build if different.
$mqtt = Join-Path $env:ProgramFiles 'Mosquitto/mosquitto_sub.exe'
& $mqtt -h 127.0.0.1 -p 51887 -v -t "$topic/heartbeat" -t "$topic/telemetry" -t "$topic/telemetry_ack" -t "$topic/command" -t "$topic/command_ack" -t "$topic/state" -t "$topic/cfg" -t "$topic/cfg_ack" -t "$topic/events"
```

In VS Code use **Wokwi: Select Config File**, choose firmware/wokwi.toml, and use **Wokwi: Start Simulator** for the demo. Verify Heartbeat, new telemetry event_id, STORED for that event_id, the Online indicator and live readings on Dashboard. In Devices, select the same ESP32 and exercise both modes and all four actuators. Match command_id in command_ack APPLIED and verify Backend status ACKNOWLEDGED and reported state before accepting success. FAN OFF must remain blocked during a safety override. Check Offline after the demo simulator disconnects. Perform those steps only for the demo simulator.

## Verification

- Frontend npm ci/lint/build PASS. Lint has React warnings and Vite reports a minified JS bundle of 743.18 kB. Playwright on source-root FE has 15 fixture passes and one optional real Backend test skipped in that invocation.
- PlatformIO esp32demo PASS. Wokwi config refers to the generated binary and ELF. Binary SHA-256 7A718ACB6B75F7FF92A25305D94898FCBBB866392B04F3A829E6C5B48F9BF16C. All nine demo topics were verified in the binary. Identity derived from demo PostgreSQL: swiftletcare/1/1/ESP32-LOCAL.
- Demo actuator bootstrap PASS: one each for AMPLIFIER, FAN, HEATER and MIST. A private pre-change pg_dump was verified with pg_restore list.
- Soak tooling regression PASS: 17 Python unit tests. Existing B3-33 logs were not touched.
- Wokwi runtime NOT TESTED: simulator UI was not driven in this session. MQTT/HTTP tests that use a test client are not Wokwi evidence.
- Backend compile/verify PASS: 85 tests, zero failures/errors/skips on new PostgreSQL 55439 and MQTT 51889. MQTT integration covers the four actuators, safety, command responses, replay handling, concurrent ACKs and sensor recovery. Neither the demo DB nor earlier QA databases were used for Maven tests.
- Source-root demo replaced the identified old Vite/Spring processes. Live FE 5173 returned HTTP 200 and BE 18085 readiness returned UP. Flyway applied V10 and V11 after the demo backup. Mosquitto confirmed client swiftletcare-source-demo on demo broker 51887. The live demo login was not attempted because the existing account password was unavailable in the test terminal.
- Current Wokwi gap: passive demo subscription timed out after 8 seconds. ESP32-LOCAL remained Offline with no last_seen_at, and PostgreSQL still had one historical telemetry row (2026-11-27T05:57:39.963798Z). Live Wokwi Heartbeat, STORED, new readings, command ACK/state and safety override remain NOT TESTED.
- Real browser/Backend E2E from source root PASS after rerun on isolated browser database. Existing 5-second Alert criterion stayed unchanged. Alert 1 matched REST, House WS and React DOM (252 ms), authenticated snapshot displayed, and an invalid token returned 401. The first attempt measured 411 ms for Alert delivery but exhausted its overall 30-second fixture budget while loading the image. The retry allowed 60 seconds for overall fixture setup and checks.
- The launcher uses environment secrets or ignored .env. The pre-existing tracked .env.local was left unchanged and excluded from the integration commit. Existing AI clients on the demo must use the same configured service key as the Backend.
