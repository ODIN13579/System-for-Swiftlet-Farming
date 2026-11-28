# BUILD3-03 — Audit và integration

Audit từ branch `AI-b3`, HEAD `7e0006433400c22df1eda3445a2008f9205ba97e`. Đã đọc Jira master plan và task P3 trong gói BUILD3 ở Downloads, OpenAPI, realtime contract, MQTT topics, migration V1–V11, handoff P4, kiến trúc/sequence và các nhánh local/remote-tracking. Working tree đã có thay đổi B3-33 và bytecode Maven. Build của P3 dùng bản sao tạm của `pom.xml` và `src`.

`git ls-remote --heads origin` xác nhận nhánh remote `AI-b3` trùng HEAD và trả thêm `be-b3`, `htran`, `integrate/frontend-htran`, `feature/build2-b2-42-43` và `main`. Phần thống kê sử dụng code đang có trên branch làm việc.

| Jira | Yêu cầu | Trước khi sửa | Test và bằng chứng P3 | Còn thiếu / phụ thuộc |
| --- | --- | --- | --- | --- |
| B3-20 | API, SQL và CSV khớp UTC+07 | Lịch sử raw phân trang, Daily Entry Audit; OpenAPI đề xuất; frontend export fixture | StatisticsIntegrationTest 3/3, StatisticsControllerTest 2/2, CSV thực từ HTTP và SQL chạy thật | P4/P5 review policy CountLog và wiring React B3-53 |
| B3-21 | Integration repeatable, Pass/Fail và bug links | Suite P4 và Build 2 có sẵn | 81 pass, 0 fail/error/skip sau khi chuẩn hóa UTC và INFO; Maven BUILD SUCCESS | [Bug tracker](bug-tracker.md) |
| B3-22 | Alert đến UI ≤5s, 50 thiết bị, auth, AI | Kịch bản NFR Build 2; Alerts React fixture | [NFR v2](nfr-build3-v2.md) | ACK burst fail, React DOM và video còn thiếu |
| B3-23 | Contract v1.2, sequence, traceability | OpenAPI, Build 2 handoff, P4 implementation | [Hợp đồng](../api/alerts-v1.2.md), [sequence](../sequence/build3-alerts.md), [traceability](../../traceability.md) | Quyết định OPEN và P5 review |
| B3-24 | Bug, risk, freeze có owner/evidence | Handoff ghi các điều kiện đóng, thiếu biên bản freeze xác minh được | [Bug](bug-tracker.md), [risk](risk-log.md), [freeze](freeze-checklist.md) | Lead xác nhận owner và biên bản 06/11 |
| B3-25 | PDF nếu ba gate Must đạt | Chưa có PDF generator được nghiệm thu | DEFERRED | B3-20/21/22 |

## Lệnh chạy lặp lại

Chạy từ repo với Docker Desktop và Maven 3.9.11/Java 21. Hai container QA sau đã được tạo riêng trong lượt này. Khi tái hiện, kiểm tra 55435/51885 trống và dùng tên mới nếu tài nguyên thuộc lượt khác. Nếu tái dùng hai container QA hiện có, xác nhận identity trước và bỏ qua phần tạo. Các test ghi fixture vào DB QA.

```powershell
$listen = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if (55435 -in $listen.Port -or 51885 -in $listen.Port) { throw 'Ports already in use. Verify intended QA containers.' }
docker run -d --name swiftletcare-p3-qa-db-20261126 -p 127.0.0.1:55435:5432 -e POSTGRES_DB=p3_qa -e POSTGRES_USER=p3qa -e POSTGRES_PASSWORD=p3-local-fixture postgres:17
if ($LASTEXITCODE) { throw 'Dedicated QA DB startup failed.' }
$config = (Resolve-Path scripts/build2-test-mosquitto.conf).Path
docker run -d --name swiftletcare-p3-qa-mqtt-20261126 -p 127.0.0.1:51885:1883 --mount "type=bind,source=$config,target=/mosquitto/config/mosquitto.conf,readonly" eclipse-mosquitto:2
if ($LASTEXITCODE) { throw 'Dedicated QA broker startup failed.' }
```

```powershell
$dbIdentity = docker exec swiftletcare-p3-qa-db-20261126 psql -XAt -v ON_ERROR_STOP=1 -U p3qa -d p3_qa -c "select current_database()||':'||current_user"
if ($LASTEXITCODE -ne 0 -or $dbIdentity -ne 'p3_qa:p3qa') { throw 'Wrong DB or role.' }
$stage = Join-Path $env:TEMP ("b3p3-verify-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $stage | Out-Null
Copy-Item backend/pom.xml,backend/src -Destination $stage -Recurse
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55435/p3_qa'
$env:DB_USERNAME = 'p3qa'
$env:DB_PASSWORD = 'p3-local-fixture'
$env:DB_TEST_ENABLED = 'true'
$env:TEST_MQTT_URL = 'tcp://127.0.0.1:51885'
$env:DEBUG = 'false'
$env:LOGGING_LEVEL_ROOT = 'INFO'
$env:P3_CSV_EVIDENCE = Join-Path $stage 'environment-sample.csv'
mvn -B -f (Join-Path $stage 'pom.xml') '-DargLine=-Duser.timezone=UTC' '-DreuseForks=false' verify *> (Join-Path $stage 'verify.log')
if ($LASTEXITCODE) { throw "Verify failed. See $stage/verify.log." }
python -B -m unittest discover -s scripts -p 'test_measure_build3.py' -v
python -B docs/api/validate_openapi.py
git diff --check
```

Các suite dùng `backend/src/test/java` theo quy ước Maven. Không sửa assertion của regression. Lượt verify đầu 74/79 pass, 5 lỗi context và 0 skipped do PostgreSQL 17 từ chối timezone `Asia/Saigon`. Retry với JVM UTC xác nhận ConfigAck và ControlCommands đều pass; ba ca mới còn vướng service key fixture ngắn hơn 24 ký tự. Sau khi sửa fixture, 3/3 Statistics pass và Maven verify Statistics BUILD SUCCESS. [Log đầu](evidence/build3-p3-20261126/verify-initial-summary.txt) · [Retry](evidence/build3-p3-20261126/verify-retry-summary.txt) · [Statistics](evidence/build3-p3-20261126/verify-statistics-summary.txt) · [CSV mẫu](evidence/build3-p3-20261126/environment-sample.csv)

Hai unit test controller pass riêng. PostgreSQL trả mẫu nhiệt độ 20 và 30 lúc 23:10/23:50, 40 lúc 00:00 ngày tiếp theo. CSV trả AVG/MIN/MAX lần lượt 25/20/30 và 40/40/40. SQL CountLog đối chứng raw ghi 102 HOURLY ngày đầu, trong đó 99 đến từ cửa sổ thử nghiệm lệch biên. API bỏ cửa sổ lệch và chọn DAILY=12 cho báo cáo ngày hoặc tuần trước khi cộng 5 của ngày tiếp theo. [Unit](evidence/build3-p3-20261126/unit-statistics-summary.txt) · [SQL output](evidence/build3-p3-20261126/statistics-oracle-output.txt)

Lượt toàn suite UTC/WARN ghi 78 pass, 1 assertion fail và 0 skipped. Nguyên nhân là lớp `DeviceEvents` log ở INFO, còn test bắt chính bản ghi đó. Runner dùng lại INFO như Build 2. Frontend lint thoát 0, có cảnh báo React hiện hữu. Kiểm định OpenAPI cục bộ pass với 49 operations, 38 paths, 70 schemas. [UTC/WARN](evidence/build3-p3-20261126/verify-utc-warn-summary.txt)

Lượt cuối với UTC/INFO **BUILD SUCCESS, 81 pass, 0 fail, 0 error, 0 skipped**. Các nhóm gồm Alert Engine 10, workflow 6, AI Predator 4, Account 5, House Role 7, Control ACK 1, Config ACK 1, Ingestion/Telemetry ACK/CountLog 13, Statistics 3 integration và 2 unit. Các suite còn lại có tổng 29 test. Python NFR 5/5 pass. [Maven log](evidence/build3-p3-20261126/maven-verify-utc-info.log)
