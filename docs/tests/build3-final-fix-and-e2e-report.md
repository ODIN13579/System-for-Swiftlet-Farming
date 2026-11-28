# Build 3 final fix and E2E

## Baseline and observed failure

The work was performed in the original repository on integration/full-system-local, initial HEAD 1934ba641e53bd352e7145301089dc69367810ef. Git was clean before edits. Existing demo FE/BE stayed running. The active demo DB and the B3-33 evidence were left unchanged, and existing services were not restarted.

The demo DB recorded d0374aee-3397-4c62-9b44-f38a1c2890a7 as REJECTED/INVALID_EXPIRY. PostgreSQL requested_at was 08:29:28.659529Z and expires_at was 08:29:38Z, a 9.340471 s remaining lifetime. ACK arrived at 08:29:29.240778Z, about 0.581 s after the request. Previous nearby commands were ACKNOWLEDGED. The same path constructs expires_at from PostgreSQL clock_timestamp at whole-second precision plus 10 s, checks expiry at claim, serializes it via Instant.toString in UTC, and pumps MQTT QoS 1 without retaining the command.

The received_at minus device timestamp for nearby ordered telemetry was 0.849, 2.902, 4.638 and 7.578 seconds. These values include transport and processing delay. The pattern and the firmware's second INVALID_EXPIRY branch strongly indicate a Wokwi clock lag relative to PostgreSQL, with about 10 s command lifetime plus more than 5 s lag exceeding the firmware's 15 s future limit. The exact Wokwi clock, SNTP callback time and raw MQTT frame for this command were unavailable, so this is a supported diagnosis pending live confirmation.

## Changes

- firmware/src/sketch.ino and firmware/include/control_core.h use one tested expiry validator and log the decision and timing for each rejected expiry. The demo requests SNTP every 15 s. A syntactically valid far-future expiry requests a rate-limited SNTP restart in the network task. The rejected command stays rejected and the duplicate cache remains in use. The PostgreSQL TTL, wire format, trusted-clock check, 15 s upper bound, GPIO safety and Telemetry ACK flow are preserved.
- frontend/src/components/FanControl.tsx shows guidance in Vietnamese for expiry and clock rejection. It keeps the reported mode and requires a new explicit command. frontend/tests/device-control.spec.ts asserts one write and no optimistic mode update on rejection.
- firmware/tests/command_expiry.cpp covers exact UTC conversion, malformed/offset/fractional strings, the 15 s boundary, delayed and expired requests including a possible redelivery after expiry, and untrusted/lagged clocks. It exercises the same validator called by sketch.ino. A physical reconnect/redelivery and duplicate-GPIO test still require Wokwi evidence.

## Acceptance

| Item | Before | After | Evidence |
|---|---|---|---|
| Telemetry realtime | PASS (reported live demo baseline) | PARTIAL | Live Heartbeat, STORED and matching DB row plus browser regression. Running simulator binary hash unverified. |
| INVALID_EXPIRY | FAIL | PARTIAL | Demo DB failure isolated and validator regression passes. New SNTP behavior not verified live. |
| AUTO to MANUAL and MANUAL to AUTO | FAIL / NOT TESTED | PARTIAL | Playwright and Backend/MQTT tests. Live APPLIED, ACKNOWLEDGED and state outstanding. |
| FAN ON/OFF | NOT TESTED | PARTIAL | Playwright exercise and Backend MQTT/ACK regression. Live GPIO/LED evidence outstanding. |
| MIST ON/OFF | NOT TESTED | PARTIAL | Playwright exercise and Backend MQTT/ACK regression. Live GPIO/LED evidence outstanding. |
| HEATER ON/OFF | NOT TESTED | PARTIAL | Playwright exercise and Backend MQTT/ACK regression. Live GPIO/LED evidence outstanding. |
| AMPLIFIER ON/OFF | NOT TESTED | PARTIAL | Playwright exercise and Backend MQTT/ACK regression. Live GPIO/LED evidence outstanding. |
| Safety override | NOT TESTED | PARTIAL | Backend and fixture falsification checks. Wokwi state and GPIO outstanding. |
| Offline/reconnect | NOT TESTED | PARTIAL | Backend timeout and browser reconnect/late-data regressions. Wokwi disconnect/reconnect outstanding. |
| Alerts realtime | NOT TESTED | PARTIAL | Alert workflow, sensor fault/recovery and cooldown covered by Backend. Browser fixtures cover House WS reconnect and notification deduplication. Real browser/API measured 74 ms for one Alert. Native notification outstanding. |
| RBAC browser | PARTIAL | PARTIAL | Unauthorized control blocked by Backend and client fixture. Account revocation and House roles covered by Maven. Full browser role matrix and session refresh are unverified. |
| NFR/soak | PARTIAL | PARTIAL | Existing soak evidence retained, tooling regression only. No new 120-minute run or Wokwi command/telemetry latency sample. |

Management (House, Zone, device and ZoneConfig), reports/CSV and accounts/profile use the existing API contracts. Backend management, config ACK, statistics, authorization and account regression tests cover these paths. Playwright checks query/CSV, session expiry and responsive layout. Bird count aggregation, camera heartbeat and snapshot/predator API have Backend coverage. Running an AI model against real camera input, complete browser CRUD/RBAC and the native Notification API are NOT TESTED. Existing lint and bundle performance warnings are recorded without unrelated refactors.

## Commands and results

- `npm run lint` and `npm run build` from frontend exited 0. Vite reports 743.31 kB for the largest JS chunk. Existing React hook/Fast Refresh warnings remain.
- `npm test -- --reporter=list --workers=2` on Edge returned 16 fixture passes and one optional real test skipped in that invocation.
- Native x64 `cl /EHsc /I<root>/firmware/include <root>/firmware/tests/command_expiry.cpp`, followed by the generated executable, exited 0.
- `python -B -m unittest discover -s scripts -p 'test_*soak.py'` passed 17 tests. Keep B3-33 PARTIAL as documented in firmware-soak-build3.md.
- `./scripts/build-wokwi-demo.ps1` passed, 17.1% RAM and 62.7% flash. MQTT prefix swiftletcare/1/1/ESP32-LOCAL. Binary SHA-256 048628CBF0FD24FD4AC58B56DC53FF0F2A473F0F7915DC78131616E1F143D246. The demo Wokwi configuration references the binary. The simulator has not loaded it under observation.
- Separate Edge test `npm test -- tests/build3-real.spec.ts --reporter=list` passed with a real isolated Backend. Alert 1 reached React DOM in 74 ms (14:10:28.174885Z to 14:10:28.248Z), the private snapshot rendered, and the invalid-token request returned 401. Initial attempts encountered Windows TCP reserved ports, so the successful run used FE 14173 and BE 18090. This one Alert sample does not establish NFR compliance.
- Maven verify exited 0 with 85 tests, zero failures/errors/skips, on a new database within the dedicated PostgreSQL 55439 instance and MQTT 51889. The run compiled both production and test sources.
- Passive demo MQTT received Heartbeat at 14:08:10Z and STORED for ESP32-LOCAL-5a09363aca723eff-24. The matching PostgreSQL row has timestamp 14:08:10Z and received_at 14:08:11.318771Z, with the ESP32 Online. This verifies the running telemetry path. The running simulator build could not be identified.
- Use the existing source-root launchers and terminal steps in [full-source-local-integration.md](full-source-local-integration.md).

## Live reproduction and acceptance

Keep FE 5173, BE 18085, PostgreSQL demo 55437 and Mosquitto demo 51887. From the source root use the demo binary already built above. In VS Code select firmware/wokwi.toml and restart the **demo** simulation only. Observe serial (especially COMMAND-CLOCK) and the demo MQTT topic:

```powershell
$mqtt = Join-Path $env:ProgramFiles 'Mosquitto/mosquitto_sub.exe'
& $mqtt -h 127.0.0.1 -p 51887 -v -t 'swiftletcare/1/1/ESP32-LOCAL/#'
```

Confirm new Telemetry and a matching STORED acknowledgement first. In Devices run AUTO → MANUAL, all eight allowed actuator actions, and MANUAL → AUTO. For every action correlate command_id, expiresAt, ACK APPLIED, Backend ACKNOWLEDGED, state report, UI and Wokwi LEDs. Repeat after disconnect and after time passes beyond the former 60-second SNTP cycle. Check that replay cannot drive GPIO again. Exercise safety override with the simulator controls and verify FAN OFF is blocked. If INVALID_EXPIRY persists, retain MQTT command/ACK and COMMAND-CLOCK for the same ID so the measured expiry-minus-device-now and SNTP age can determine the branch and follow-up.
