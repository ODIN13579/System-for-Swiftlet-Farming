# Build 3 completion audit

**PARTIAL — no freeze or release approval recorded.** Jira source: `C:/Users/ACER/Downloads/SwiftletCare_BUILD3_PhanCong_Theo_Ten_Cong_Viec.docx`. BUILD3-00 is the common prerequisite; the 34 numbered rows below follow the document priorities.

Source root `D:/New_folder/System-for-Swiftlet-Farming`, branch `integration/full-system-local`, start and end HEAD `1934ba641e53bd352e7145301089dc69367810ef`. Changes remain local. Preserved original modified expiry/UI files, two camera configs and sample-02. External backup of the pre-existing diff and AI entrypoint: `%TEMP%/swiftletcare-build3-audit-f5e36aea`. Restored scoped handoff docs and existing evidence from `AI-b3` and the saved stash without applying or dropping it. New code: `ai/src/main.py`, `ai/src/counting.py`, `ai/tests/test_counting.py`. Reused `scripts/measure-build3.py` and its tests and blocked the demo broker in its load/alert commands. Reports, references and evidence are under `docs/` and `traceability.md`. No new commit, merge or push.

## BUILD3-00

Prior live demo evidence verified Telemetry/STORED and DB persistence. Existing command expiry fix shortens SNTP sync without relaxing TTL or safety. This turn used regression and passive/read-only demo checks. Full control and ZoneConfig through a verified current Wokwi binary, real GPIO, reconnect, and firmware/DB/DOM command ID correlation remain **PARTIAL**. Current firmware build SHA-256 `048628CBF0FD24FD4AC58B56DC53FF0F2A473F0F7915DC78131616E1F143D246` is a file hash, and simulator binary identity was not observed.

## Jira acceptance matrix

| Jira | Priority | Requirement | Code | Test | Evidence | Status | Blocker / owner |
|---|---|---|---|---|---|---|---|
| B3-30 | Must | Wokwi alerts and recovery | sketch.ino / AlertEngine | Backend MQTT regression | firmware-alerts-build3.md | PARTIAL | P1/P4: collect Serial/MQTT, alert ID, DOM and Wokwi safety for seven cases |
| B3-31 | Must | Temperature and CO2 rationale | ZoneConfigController / sketch.ino | Source and threshold review | build3-threshold-review.md | PASS (documentation) | Lead/GV: approve values before any firmware change |
| B3-32 | Must | Wiring, BOM and safety | firmware/diagram.json / docs/hardware | Wokwi diagram/GPIO source review | ../hardware/wiring.md | PARTIAL | P1: finish referenced estimates and confirm selected real parts |
| B3-33 | Should | 120-minute soak | scripts/capture-firmware-soak.py | Tooling tests | firmware-soak-build3.md | PARTIAL | P1/P3: single 120-minute run with BOOT/RESOURCE and hash |
| B3-34 | Should | Configured CO2 and light | ZoneConfigController / AlertEngine | Regression of configured thresholds | build3-threshold-review.md | DEFERRED | Lead/GV: approve thresholds and contract |
| B3-35 | Could | PID comparison | Existing AUTO rules | Outside current gate | Jira B3-35 | DEFERRED | P1: follow-up after Must |
| B3-40 | Must | Predator gate and snapshot | ai/src/predator.py | Real 80-frame owl/snake/rat/negative clips | ../ai/eval-v2.md | PARTIAL | P2: confirm labelled rat video and all supported species |
| B3-41 | Must | Upload and durable retry | ai/src/predator.py / AiPredatorService | Outbox regression and prior real outage/retry evidence | ../ai/eval-v2.md | PASS (technical) | P2/P4: formal review |
| B3-42 | Should | Camera heartbeat | PredatorMonitor / CameraHeartbeatService | Python and Backend regression | ../ai/eval-v2.md | PARTIAL | P2/P4: camera or live stream interruption |
| B3-43 | Must | Predator evaluation | ai/tools/eval_build3.py | 87 labelled images, per-class IoU metrics | evidence/build3-completion-ai-20261127/predator-box-metrics.csv | PARTIAL | P2/GV: independent negative video, false alerts per hour and targets |
| B3-44 | Must | Improve bird count vs Build 2 | ai/src/counting.py / ai/src/main.py | Geometry regression and offline same-clip A/B | evidence/build3-completion-ai-20261127/ | PARTIAL | P2: annotated crossings, error calculation and approved real CountLog run |
| B3-45 | Must | Dataset and model card | docs/ai/dataset.md / model-card.md | Weights hash and archive split provenance | ../ai/model-card.md | PARTIAL | P2/Lead: confirm licensing and source-level independence |
| B3-46 | Could | On-demand live video | Existing offline and heartbeat pipelines | Outside current gate | Jira B3-46 | DEFERRED | P2: streaming dependency and review |
| B3-20 | Must | Statistics and CSV | StatisticsController | PostgreSQL JSON/CSV UTC+7 oracle, CountLog and pagination regression | build3-p3-integration.md | PASS (technical) | P3: formal review |
| B3-21 | Must | Build 3 test regression | Backend integration suite and frontend tests | 85 Maven, 16 fixtures and five isolated real browser repetitions | evidence/build3-completion-alert-dom-20261127.txt | PASS (technical) | P3: handoff and complete any separately required Build 1 scenarios |
| B3-22 | Must | NFR v2 | TelemetryAckPublisher / measure-build3.py | 50 clients, 150 exact IDs and five real DOM samples | nfr-build3-v2.md | PARTIAL | P3/P4: repeat ACK latency on controlled load and complete video NFR |
| B3-23 | Must | Contract and traceability | OpenAPI, Alert contract, sequence and matrix | OpenAPI validator and internal refs check | ../api/alerts-v1.2.md | PARTIAL | P3/Lead: resolve WS optional fields, P35/P36 and contract approval |
| B3-24 | Must | Triage, risk and freeze | Bug tracker, risk log and freeze checklist | Current engineering entries | freeze-checklist.md | PARTIAL | Lead: perform review and record formal freeze or follow-up tickets |
| B3-25 | Could | PDF reports | JSON and CSV paths only | Gate pending | Jira B3-25 | DEFERRED | P3: after B3-20/21/22 |
| B3-10 | Must | Four alert classes and recovery | AlertEngine / CameraHeartbeatService | Engine, Ingestion and safety-related tests | firmware-alerts-build3.md | PARTIAL | P4/P1: real Wokwi scenarios and correlated recovery |
| B3-11 | Must | Alert REST, workflow, House WS | AlertController / HouseAlertRealtime | Status transitions, RBAC and five browser Alert deliveries | evidence/build3-completion-alert-dom-20261127.txt | PASS (technical) | P4: formal review |
| B3-12 | Must | Private snapshot and Predator events | AiPredatorController / AiPredatorService | Auth, payload, replay, private image and browser retrieval | ../ai/eval-v2.md | PASS (technical) | P4: formal review |
| B3-13 | Must | Account management | AccountController / AuthController | Create, deactivate, old password and JWT revocation | build3-p3-integration.md | PASS (technical) | P4: formal review |
| B3-14 | Should | Camera offline | CameraHeartbeatService | Scheduler and HTTP regression | ../api/alerts-v1.2.md | PARTIAL | P2/P4: physical or live video example |
| B3-15 | Should | FLOCK_DROP | DailyEntryAudit coverage only | Backend coverage checks | ../api/alerts-v1.2.md | DEFERRED | Lead/GV: P35/P36 and multi-camera decision |
| B3-16 | Should | Role reassignment | HouseMembershipController | Immediate scope and old credential tests | ../api/alerts-v1.2.md | PASS (technical) | P4: formal review |
| B3-17 | Should | Cleanup and query tuning | SnapshotCleanup / migration V11 | Snapshot lifecycle and access regression | ../api/alerts-v1.2.md | PARTIAL | P4: retention approval and measured history EXPLAIN |
| B3-50 | Must | Alert Center | Alerts / AlertSession | Five real PREDATOR deliveries, scope/reconnect fixtures | evidence/build3-completion-alert-dom-20261127.txt | PARTIAL | P5: live Wokwi, real UI workflow and error cases |
| B3-51 | Must | Browser Notification | browserNotifications / AlertSession | Fixture grant, denial, toggle, dedup and wrong House | Playwright build3.spec.ts | PARTIAL | P5: native browser notification evidence |
| B3-52 | Must | Dashboard v3 and CountLog | Dashboard / DashboardAlerts / AiCounts | Offline counts, browser scope tests and demo SQL | ../ai/eval-v2.md | PARTIAL | P2/P5: real AI clip-to-CountLog-to-DOM match and five-image check |
| B3-53 | Must | Reports and CSV | Reports / StatisticsController | SQL/API/CSV Backend assertions, browser request fixture | build3-p3-integration.md | PARTIAL | P3/P5: real browser chart/API/CSV matching data set |
| B3-54 | Must | Admin UI/profile | AdminUsers / Profile | Backend auth and browser 401 fixture | full-source-local-integration.md | PARTIAL | P5/P4: real browser Admin/Owner/Viewer matrix |
| B3-55 | Should | Responsive and UX flow | Frontend layout | 360/768/1024/1440 fixture | Playwright build3.spec.ts | PARTIAL | P5: test with 3–5 actual participants |
| B3-56 | Could | Live video frontend | Depends on B3-46 | Outside current gate | Jira B3-56 | DEFERRED | P5: after B3-46 |

Technical PASS covers the listed acceptance evidence. Overall review, release gate and transfer ticket IDs remain pending. Before this run, load and one DOM measurement had historical evidence. Current evidence adds independent load reconciliation, repeat DOM measurement, video inference and AI geometry tests. Wokwi and annotated counting gates remain open.

| Group | Must complete in technical scope | Must pending | Should pending | Could deferred | Status |
|---|---:|---:|---:|---:|---|
| BUILD3-01 | 1 | 2 | 2 | 1 | PARTIAL |
| BUILD3-02 | 1 | 4 | 1 | 1 | PARTIAL |
| BUILD3-03 | 2 | 3 | 0 | 1 | PARTIAL |
| BUILD3-04 | 3 | 1 | 3 | 0 | PARTIAL |
| BUILD3-05 | 0 | 5 | 1 | 1 | PARTIAL |

## Confirmed defects and evidence

- CountLog geometry: demo config `[960,100,960,650]` on `sample-02.mp4` 568 × 320 made crossing impossible. Before fix the full offline run reported Entry 0, Exit 0, Detect 15 (unique ByteTrack IDs). Validation now rejects invalid geometry and accepts explicit normalized calibration. The experimental center-line `[0.5,0.125,0.5,0.875]` still yielded 0/0 with 15 tracks and 103 track observations. No ground truth for that line was approved, so this is not evidence of improved crossing accuracy. A read-only demo query did find one existing HOURLY CountLog for CAMERA-LOCAL id 2, with counts 5/5/214; this row was not generated by the offline run and does not establish why the old UI time range was empty. No demo writes.
- Same first 80 frames of `swiftlet-test1`, resolution 1920 × 720, imgsz 640: Build 2 `swiftlet_v1` gave 14 ENTRY / 0 EXIT (81 unique IDs). Build 3 `combined_4class_v1` gave 15 / 0 (104 unique IDs). The observed difference of one entry cannot be classified as more accurate without manually annotated crossings. Both runs were intentionally partial and submitted no CountLog.
- Offline artifact isolation: `--artifact-dir` creates a new directory with annotated output and per-track log. Offline predator outbox is placed there when this option is set, keeping demo retry data untouched. Existing configs were left intact. [AI outputs](evidence/build3-completion-ai-20261127/)
- Predator: 87 test images evaluated, with byte-duplicate check across splits. Precision/recall: snake 57.14%/70.59%, rat 66.67%/100% on two ground-truth rat boxes, owl 77.78%/53.85%. Actual 80-frame clip runs emitted owl=1 from owl-test, snake=1 from snake-test, zero predator events from both rat-test and swiftlet-test1. [Video output and outbox counts](evidence/build3-completion-ai-20261127/predator-video-summary.json) Photos and short smoke clips do not establish false alerts/hour. [Evaluation](../ai/eval-v2.md)
- P3-MQTT-01: Current ACK worker reproduced no lost ACKs under 50 QA publishers and 150 events. PostgreSQL, published IDs and STORED IDs matched exactly, without duplicate, timeout or publish error. ACK p50/p95 6,013.75/7,447.29 ms on shared QA host. The latency remains open. [Raw results](evidence/build3-completion-load-20261127/result.json) · [Reconciliation](evidence/build3-completion-load-20261127/reconciliation.json)
- PREDATOR real Edge/API: five createdAt-to-React samples 106, 70, 82, 72, 88 ms, linear p50 82 ms, p95 102.4 ms. Per test also checks authenticated snapshot and invalid-token 401. This narrowly meets the 5-second target for these five test events. [Browser output](evidence/build3-completion-alert-dom-20261127.txt)

## Commands and regression

- Backend: `mvn -B -Dswiftletcare.build.directory=<QA-output> verify`, DB `swiftletcare_completion_qa` on 55439, MQTT on 51889. 85 passed, zero skipped. Traffic for B3-22 used separate database `swiftletcare_completion_load`.
- Frontend: `npm run lint`, `npm run build`, `npm test -- --reporter=list --workers=2`: 16 fixtures pass, optional Backend test skipped in that invocation. Ran separately `npm test -- tests/build3-real.spec.ts --repeat-each=5 --workers=1`: 5 pass. Existing lint warnings and 743.50 kB build warning remain.
- AI: from `ai`, `.venv/Scripts/python.exe -B -m pytest -q tests`: 25 pass. Run only the test directory; a broad pytest scan encounters permissions under an existing outputs directory. `tools/eval_build3.py` and `src.main` were executed on available weights/media. No test payload was submitted to demo.
- Firmware: `./scripts/build-wokwi-demo.ps1` PASS (RAM 17.1%, flash 62.7%), run confirms topic `swiftletcare/1/1/ESP32-LOCAL`. Existing soak session was not changed. Firmware binary was not shown running in Wokwi. Native C++ command expiry test passed. Separate soak/NFR tooling unit suite passed 23 tests.
- Docs: OpenAPI YAML parse plus 355 local references resolved across 38 paths. openapi-spec-validator returned OK. Lead contract signoff remains pending. `git diff --check` passed on tracked diff.

## Runbook and handoff

FE/BE/Wokwi can start from the existing source-root launchers documented in [local integration runbook](full-source-local-integration.md). Wokwi operator uses firmware/wokwi.toml and collects same-ID command/ACK/state plus GPIO. For B3-30 follow [firmware alert evidence plan](firmware-alerts-build3.md). For a separate soak use the [B3-33 runbook](firmware-soak-build3.md), preserve old evidence and collect BOOT, heap/reconnect/reset and monotonic timing for the complete window.

AI offline example from the `ai` directory, using a new path. A config or copy must already match the clip resolution and camera.

```powershell
$out = Join-Path $env:TEMP ('swiftletcare-ai-' + [Guid]::NewGuid())
& .venv/Scripts/python.exe -B -m src.main --config configs/build3-cam.example.json --model '<approved checkpoint path>' --video '<labelled input clip>' --artifact-dir $out --no-send
```

Lead/GV decisions and open acceptance items are tracked above and in the [bug tracker](bug-tracker.md), [risk log](risk-log.md) and [freeze checklist](freeze-checklist.md). Authorization to access the demo password or drive the current Wokwi simulator was unavailable. Missing physical evidence, labels and approval keep Build 3 PARTIAL.

## AI follow-up 2026-11-28

B3-40, B3-43, B3-44 and B3-45 remain PARTIAL. The follow-up distinguishes the two counting clips, adds frame overlays and review candidates, audits cross-split near matches, and provides a negative-video rate tool. See [AI follow-up report and Jira status](../ai/build3-ai-fix-report.md).
