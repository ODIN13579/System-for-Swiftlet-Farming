# B3-33 — Soak test firmware BUILD 3

**B3-33 PARTIAL.** Capture MQTT ngày 25/11/2026 xác nhận có Heartbeat, State và Telemetry. Timestamp Heartbeat đầu và cuối cách nhau 180 phút 04 giây, với 21 khoảng trên 5 giây. **MISSING EVIDENCE** cho Serial, free heap, số lần reconnect và reset reason. Lượt rà soát này phân tích bằng chứng có sẵn và chưa chạy Wokwi.

## Phiên chính thức đang thu ngày 27/11/2026

Phiên `D:/swiftletcare-soak/b3-33/official-20261127-071107-e693588a` ghi BOOT ban đầu với `reset_reason=1` lúc 10:45:51,916 giờ Việt Nam, sau Serial attach và MQTT SUBACK. Thời điểm bấm GDB `continue` chưa được ghi trực tiếp. Timeline lưu quan sát BOOT riêng và ghi START lúc 10:47:07,194 sau khi xác nhận State AUTO, RESOURCE và ACK khớp qua Telemetry, STORED và Serial. Checkpoint RESOURCE đầu tiên nằm trong khung START có uptime 120.011 ms, nhận lúc 10:47:54,541. **PARTIAL** trong khi chờ đủ thời lượng và audit cuối lượt.

## Nguồn bằng chứng

| Tệp | Kết quả kiểm tra |
| --- | --- |
| `D:/swiftletcare-soak/b3-33/mqtt-soak.log` | Đọc trực tiếp bằng Python hết 43.377.116 byte, 175.038 dòng thiết bị hợp lệ. Kích thước trước và sau đọc bằng nhau. |
| `D:/swiftletcare-soak/b3-33/mqtt-soak-snapshot.log` | Snapshot có sẵn, 43.377.116 byte, cùng SHA-256 với log gốc. |
| `D:/swiftletcare-soak/b3-33/commit.txt` | 42 byte. Commit ghi khi chuẩn bị là `7e0006433400c22df1eda3445a2008f9205ba97e`, trùng HEAD branch `AI-b3`. Git sạch trước khi sửa. Chưa có artefact gắn binary Wokwi với commit. |
| `D:/swiftletcare-soak/b3-33/setup-time-utc.txt` | 30 byte. Mốc chuẩn bị `2026-11-25T12:36:07.3735791Z`. |
| `D:/swiftletcare-soak/b3-33/serial-soak.log` | **MISSING EVIDENCE**. Không tìm thấy Serial thật trong thư mục bằng chứng hoặc phần workspace đọc được. Thư mục `ai/outputs/b230_sessions` từ chối truy cập. Hai fixture `soak-synthetic-*.log` bị loại. |
| `D:/swiftletcare-soak/b3-33/b3-33-mqtt-analysis.json` | Bản phân tích mới, gồm số liệu theo topic và từng khoảng Heartbeat trên 5 giây. |

SHA-256 log gốc và snapshot là `2596d55b9f4ad4df79ecdd745c46dcaba280d6f7790948994def826a1a459ac6`.

Đối chiếu mục BUILD3-01 / B3-33 trong bản `BUILD3_JIRA_MASTER_PLAN.md` thuộc gói BUILD3 đã giải nén ở Downloads. Bản có hậu tố `(1)` chưa tìm thấy. Tiêu chí Jira ghi “Không có reset ngoài ý muốn trong đợt đo; nếu có phải ghi lỗi và tái hiện.” Công việc còn yêu cầu Wokwi liên tục ít nhất hai giờ với MQTT và Telemetry, quan sát free heap, số lần reconnect/reset, ghi thời gian và phiên bản firmware, bàn giao báo cáo cùng log.

Lượt trước trong tài liệu `3.33.docx` được ghi 21 phút 06 giây với boot ID `e599169fabf1f848` và có chuyển MANUAL sang AUTO. Lượt đó có hai checkpoint heap và một chỗ gán mốc heap không thống nhất. Số liệu của lượt trước và các checkpoint minh họa nằm ngoài kết quả ngày 25/11.

## Kết quả MQTT ngày 25/11/2026

Thiết bị `ESP32-HOUSE01-ZONE01`, topic base `swiftletcare/1/1/ESP32-HOUSE01-ZONE01`. Các thời điểm bên dưới lấy từ timestamp trong payload thiết bị. Capture dạng topic và JSON không chứa timestamp phía nhận. Thứ tự dòng cho biết thứ tự ghi trong tệp. Khoảng cách timestamp chưa xác minh được độ trễ, thời gian mất kết nối hoặc tính liên tục theo đồng hồ thực.

| Topic | Bản tin | Timestamp lặp | Đảo chiều | Sai định dạng | Thiếu/null |
| --- | ---: | ---: | ---: | ---: | ---: |
| Heartbeat | 3.694 | 0 | 0 | 0 | 0 |
| Telemetry | 169.859 | 169.739 | 26.219 | 0 | 0 |
| Telemetry ACK | 0 | 0 | 0 | 0 | 0 |
| State | 1.485 | 3 | 0 | 0 | 1 |
| Events | 0 | 0 | 0 | 0 | 0 |

Timestamp lặp được tính khi giá trị UTC đã xuất hiện trên cùng topic. Đảo chiều được tính so với timestamp hợp lệ liền trước của cùng topic trong thứ tự dòng. Cả hai phép đếm có thể áp dụng cho cùng một bản tin.

- Heartbeat đầu `2026-11-25T13:48:53Z`, cuối `2026-11-25T16:48:57Z` (20:48:53–23:48:57 giờ Việt Nam), chênh 10.804 giây. Có 21 khoảng >5 giây, 19 khoảng >10 giây, 15 khoảng >30 giây. Các nhóm ngưỡng lồng nhau.
- Khoảng lớn nhất 997 giây, từ 16:05:59Z đến 16:22:36Z. Các khoảng lớn tiếp theo gồm 383 giây (16:34:37Z–16:41:00Z), 346 giây (15:38:40Z–15:44:26Z) và 328 giây (15:14:59Z–15:20:27Z). Chưa có bằng chứng xác định nguyên nhân các khoảng trống.
- 1.485 bản tin State đều ghi `boot_id=5ee4bba5c8c33357` và `mode=AUTO`. Có 0 lần đổi boot ID quan sát được và không thấy MANUAL. Firmware tạo boot ID khi setup và gửi qua State. Heartbeat không mang boot ID nên khoảng giữa các lần quan sát State vẫn cần Serial hoặc dấu vết tương đương để kiểm tra reset.
- Telemetry có 120 `event_id` duy nhất. Mỗi ID xuất hiện 1.356–1.475 lần. 120 timestamp Telemetry duy nhất trải từ 13:48:54Z đến 13:59:15Z, dài 10 phút 21 giây. Mẫu lặp phù hợp với cơ chế gửi lại dữ liệu đệm trong firmware. Chưa xác minh được nguyên nhân từ capture.
- `STORED` đối chiếu được 0/120 ID (0% trong capture). Backend phát ACK sau thao tác lưu và firmware dùng ACK hợp lệ để bỏ bản tin khỏi đệm. Capture thiếu ACK nên chưa xác định được bộ thu có đăng ký topic này hay việc lưu, phát và nhận ACK đã diễn ra.
- Không có field uptime, free heap, minimum heap, reconnect counter hoặc reset reason trong các payload thu được. Firmware ghi các chỉ số qua `[RESOURCE]` và ghi reset reason qua `[BOOT]`. `connects` bao gồm cả lần kết nối thành công đầu tiên.
- Uptime ghi tay `7380675 ms` tương đương 123 phút 00,675 giây, thấp hơn span Heartbeat 57 phút 03,325 giây. Chưa khôi phục được dòng Serial và thời điểm ghi liên quan để gắn uptime với Heartbeat đầu/cuối hoặc xác định nó là mẫu cuối. Timestamp Heartbeat lấy từ đồng hồ UTC thiết bị, còn uptime dùng `millis()`. Chênh lệch hiện chưa xác định được nguyên nhân. [Firmware](../../firmware/src/sketch.ino)

## Đối chiếu nghiệm thu

| Tiêu chí | Trạng thái | Bằng chứng |
| --- | --- | --- |
| Có MQTT Heartbeat, State và Telemetry trong capture | PASS | Các topic và ID được kiểm tra toàn bộ log. |
| Wokwi liên tục ≥120 phút với MQTT và Telemetry | PARTIAL | Span timestamp Heartbeat đạt 180:04, có khoảng trống đến 997 giây và timestamp Telemetry duy nhất chỉ trải 10:21. Thiếu log Wokwi/Serial cùng khung đo. |
| Quan sát free heap | NOT TESTED | MISSING EVIDENCE. Thiếu checkpoint heap thật. |
| Quan sát số lần reconnect | NOT TESTED | MISSING EVIDENCE. Thiếu bộ đếm Serial hoặc broker log của phiên thiết bị. |
| Không reset ngoài ý muốn, nếu có thì ghi lỗi và tái hiện | PARTIAL | Một boot ID được quan sát qua State. MISSING EVIDENCE cho reset reason và phạm vi boot xuyên suốt. Chưa xác nhận một reset để tái hiện. |
| Ghi thời gian, phiên bản và kết quả | PARTIAL | Có mốc chuẩn bị, timestamp payload, commit và báo cáo. Chưa có mốc chạy Wokwi/Serial và định danh binary cùng lượt. |
| Bàn giao báo cáo và log | PARTIAL | Có báo cáo, MQTT log và JSON. MISSING EVIDENCE cho Serial. |

PASS nghĩa là tiêu chí có bằng chứng đạt. PARTIAL nghĩa là có bằng chứng nhưng phạm vi chưa đủ để xác nhận. FAIL dành cho vi phạm được xác minh. NOT TESTED nghĩa là chưa có dữ liệu đo hợp lệ cho tiêu chí. Chưa có căn cứ xác nhận một FAIL thuộc tiêu chí reset của Jira.

## Phục hồi và kiểm tra lại

1. Trong cửa sổ Wokwi đã chạy, kiểm tra Serial Monitor, VS Code Output và Terminal, cùng thư mục log tải xuống. Nếu phiên hoặc bản xuất vẫn còn, lưu nguyên văn cùng mốc UTC, định danh phiên và đường dẫn binary. Tìm `[BOOT]` với `reset_reason`, `[RESOURCE]` với uptime, free/min heap, connects/attempts và `[MQTT] CONNECTED`. Đối chiếu dòng uptime gần 7.380.675 ms với mốc Heartbeat và ghi chú reset chủ đích nếu có.
2. Nếu Serial đã mất, tìm log Wokwi/cổng serial đã lưu và broker log cùng phiên để khôi phục mốc nhận hoặc các lần kết nối. Cần nguồn đo được heap, reset và uptime trên cùng lượt để chốt nghiệm thu.
3. Nếu không thể phục hồi bằng chứng tương đương, chạy lại Wokwi tối thiểu 120 phút. Trước khi khởi động, mở lưu Serial và subscriber trên broker kiểm thử. Ghi commit, hash binary, mốc bắt đầu/kết thúc UTC, mode và hành động chủ đích. Thu `heartbeat`, `telemetry`, `telemetry_ack`, `state` và `events`, kèm timestamp phía nhận nếu cần chứng minh thời gian thực. Kiểm tra ngay lúc bắt đầu xem có `[BOOT]`, `[RESOURCE]` và ACK `STORED`. Nếu có reset ngoài ý muốn, lưu đoạn log liên quan, ghi lỗi và tái hiện theo Jira. [Cấu hình Wokwi](../../firmware/wokwi.toml) · [Topic MQTT](../mqtt/topics.md)

## Lệnh đã chạy

```powershell
python -B -m unittest discover -s scripts -p test_audit_firmware_soak.py -v
python -B scripts/audit-firmware-soak.py --serial D:/swiftletcare-soak/b3-33/serial-soak.log --mqtt D:/swiftletcare-soak/b3-33/mqtt-soak.log --topic-base swiftletcare/1/1/ESP32-HOUSE01-ZONE01 --output D:/swiftletcare-soak/b3-33/b3-33-mqtt-analysis.json
git diff --check
```

Sáu unit test đạt. Lệnh phân tích đọc trọn log thật, ghi JSON mới và ghi rõ Serial thiếu. `git diff --check` đạt. Công cụ trả `REQUIRES REVIEW` để giữ quyết định nghiệm thu ở báo cáo.

## PRE-FLIGHT FIX ngày 26/11/2026

**B3-33 PARTIAL.** Đã thử công cụ thu MQTT trên Windows. Pre-flight Wokwi 3–5 phút, Serial từ boot và ACK end-to-end với backend test vẫn chờ thực hiện.

### Chẩn đoán Telemetry và ACK

Capture cũ chứa đúng dải số thứ tự ID 1–120, trùng sức chứa buffer RAM. Toàn bộ bản sao của mỗi ID giữ nguyên payload. Cả 120 payload qua đối chiếu offline với schema hiện tại, bao gồm ID thiết bị, timestamp có timezone, dải đo và `sensor_status`. Kiểm tra offline chưa bao gồm retained flag, giờ backend khi nhận và trạng thái DB.

Firmware tạo ID cho từng mẫu mới, đưa cả topic và payload vào buffer rồi thử gửi lại với khoảng cách tối thiểu 5 giây theo `millis()` cho từng ID. Thao tác đưa vào queue TX hoặc publish không giải phóng slot. Khi đầy 120 slot, mẫu mới bị bỏ và bộ đếm `dropped` tăng. Firmware chỉ xóa khi xử lý ACK `STORED` khớp topic, `device_id` và `event_id`. Dải 1–120 lặp nguyên payload phù hợp với buffer đầy mà các phần tử không được giải phóng. [Firmware](../../firmware/src/sketch.ino)

Backend subscribe `swiftletcare/+/+/+/telemetry`. Gateway xác thực topic/payload, gọi `Ingestion.store` rồi gửi ACK QoS 1, không retain, về cùng topic base với hậu tố `telemetry_ack`. ID trùng cùng payload được ACK lại sau khi xác minh bản ghi. Ingestion cần ESP32 enabled, House/Zone đúng và active, timestamp không vượt giờ backend quá 300 giây. Lỗi validation, DB, xử lý sau commit hoặc publish có thể ngăn ACK. Gateway hiện chỉ log lớp lỗi chung. [Gateway](../../backend/src/main/java/com/swiftletcare/iot/MqttGateway.java) · [Ingestion](../../backend/src/main/java/com/swiftletcare/iot/Ingestion.java)

| Giả thuyết | Bằng chứng cần đối chiếu trong pre-flight |
| --- | --- |
| Backend chưa chạy, MQTT tắt hoặc sai broker | Readiness DB trả UP, broker test có CONNECT của backend và SUBSCRIBE/SUBACK cho wildcard. Nếu readiness UP nhưng thiếu CONNECT, kiểm tra URL, cờ MQTT, credential và client ID. |
| Thiết bị chưa đăng ký hoặc sai House/Zone | Truy vấn fixture DB test và xác minh House 1, Zone 1, đúng ID, active và enabled. |
| Sai topic, ACL hoặc subscriber cũ bỏ lọt ACK | So topic và SUBACK của backend, ESP32 và collector tại broker. Cặp topic trong mã hiện tại khớp. Nếu broker phát ACK nhưng collector thiếu, kiểm tra filter và ACL trước. |
| Sai payload hoặc lỗi xử lý/lưu | Kiểm tra mẫu live, giờ UTC hai bên, retained flag, warning gateway và bản ghi theo `event_id` ở DB test. Nếu đã lưu mà chưa có ACK, kiểm tra xử lý sau commit và đường publish. |
| Firmware không nhận hoặc xử lý ACK | Nếu broker có ACK cho cùng ID, đối chiếu Serial `[BUFFER] Removed STORED`, log reject, subscribe ACK và `rx_drop`. |

**Đã xác định cơ chế gửi lặp. Điểm mất ACK của lượt cũ chưa xác minh.** Capture không có ACK và thiếu cấu hình subscriber, trace gateway/broker và Serial cùng lượt. Trạng thái tiến trình hiện tại không chứng minh trạng thái ngày 25/11. Firmware và backend giữ nguyên.

### Đối chiếu uptime

Heartbeat lấy UTC từ `time(nullptr)` sau SNTP. `[RESOURCE]` lấy uptime từ `millis()`. Mẫu ghi tay 7.380.675 ms không có dòng Serial kèm UTC để biết nó nằm ở đâu trong capture. Chênh 57 phút 03,325 giây chưa giải thích được. Boot ID không đổi trong các State quan sát được. Tính năng tạm dừng khi tab Wokwi không hiển thị là một khả năng cần kiểm tra, chưa có dấu vết nó xảy ra trong lượt cũ. Khi chạy mới, giữ tab hiển thị, ghi UTC Windows khi resume/dừng, và gõ `TEST STATUS` trong Serial Monitor của Wokwi sau SNTP và trước khi dừng. Hai dòng `[TEST] utc=... uptime_ms=...` cho phép đối chiếu cùng `[RESOURCE]` và UTC nhận Heartbeat. Ghi chú mọi lần pause/resume. [Wokwi project config](https://docs.wokwi.com/vscode/project-config)

### Windows 11 — Terminal 1, kiểm tra Windows Mosquitto và tạo Postgres riêng

| Cổng | Dịch vụ trong lượt này | Kiểm tra trước khi chạy |
| --- | --- | --- |
| 1883 | Windows Mosquitto hiện có. Backend dùng `127.0.0.1`, Wokwi dùng `host.wokwi.internal` qua Private IoT Gateway. | Listener loopback đã có. Kiểm tra CONNACK/SUBACK và xác nhận dùng chung broker bằng ACK end-to-end trước khi bấm giờ. |
| 55434 | Postgres 17 riêng, project `b333-preflight`, database `b333_preflight`, volume `b333-preflight_pgdata`. | Trống khi rà soát. Recheck trước khi bind. |
| 18083 | Backend test trên Windows, chỉ bind loopback. | Trống khi rà soát. Recheck trước khi chạy. |
| 4000 / 3333 | Serial RFC2217 / GDB của Wokwi staging. | Trống khi rà soát. Recheck trước khi mở Wokwi. |

Giữ nguyên các cổng `55432`, `51883`, `55433`, `51884` và `5433`. Compose Build 2 ghim trực tiếp cổng `55433`, nên đổi project name của file đó vẫn va chạm cổng. Phần bên dưới tạo Compose Postgres-only riêng từ đầu.

Điều kiện dùng broker chung là xác minh qua danh sách ứng dụng/cấu hình triển khai rằng broker Windows dành cho kiểm thử, không có backend/automation đang ghi vào dữ liệu thật từ topic này, và không có thiết bị hoặc phiên khác dùng client ID `ESP32-HOUSE01-ZONE01`. Nếu chưa xác minh được thì **FAIL — dừng trước khi chạy backend hoặc resume Wokwi**. Subscribe probe chỉ kiểm tra kết nối/quyền đọc. [Wokwi Private Gateway](https://docs.wokwi.com/guides/esp32-wifi)

Chạy từ repo. Dùng Docker context hiện có của Docker Desktop và kiểm tra danh sách container trước khi tạo tài nguyên mới. Compose file nằm trong thư mục tạm riêng. Nếu project hoặc volume đã tồn tại, lệnh dừng để xác minh nguồn gốc trước khi tiếp tục.

```powershell
$ErrorActionPreference = 'Stop'
docker ps --format '{{.Names}} {{.Ports}}'
if ($LASTEXITCODE -ne 0) { throw 'FAIL: Docker Desktop không truy cập được.' }
$ports = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if (55434 -in $ports.Port) { throw 'FAIL: cổng 55434 đã được sử dụng.' }

$sub = 'C:/Program Files/mosquitto/mosquitto_sub.exe'
$base = 'swiftletcare/1/1/ESP32-HOUSE01-ZONE01'
& $sub -h 127.0.0.1 -p 1883 -d -E -W 5 -q 1 -t "$base/telemetry_ack" -t 'swiftletcare/+/+/+/telemetry' -t 'swiftletcare/+/+/+/heartbeat' -t 'swiftletcare/+/+/+/events'
if ($LASTEXITCODE -ne 0) { throw 'FAIL: MQTT CONNECT/SUBSCRIBE thất bại.' }
```

Chỉ tiếp tục khi CONNACK bằng 0, mọi SUBACK được cấp QoS và đã kiểm tra xong điều kiện broker chung. Nếu SUBACK hiện 128 hoặc báo từ chối, dừng và kiểm tra ACL. Không publish probe lên topic của thiết bị.

```powershell
$stack = 'b333-preflight'
$existing = docker ps -aq --filter "label=com.docker.compose.project=$stack"
if ($LASTEXITCODE -ne 0 -or $existing) { throw 'FAIL: không xác minh được project mới.' }
$volumes = docker volume ls -q
if ($LASTEXITCODE -ne 0 -or "${stack}_pgdata" -in $volumes) { throw 'FAIL: volume đã tồn tại hoặc không đọc được.' }
$networks = docker network ls --format '{{.Name}}'
if ($LASTEXITCODE -ne 0 -or "${stack}_default" -in $networks) { throw 'FAIL: network đã tồn tại hoặc không đọc được.' }

$work = Join-Path $env:TEMP ("b333-db-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -ErrorAction Stop | Out-Null
$compose = Join-Path $work 'compose.yml'
@'
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: b333_preflight
      POSTGRES_USER: b333_test
      POSTGRES_PASSWORD: b333-test-local-only
    ports:
      - "127.0.0.1:55434:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "b333_test", "-d", "b333_preflight"]
      interval: 2s
      timeout: 3s
      retries: 30
volumes:
  pgdata:
'@ | Out-File -LiteralPath $compose -Encoding ascii -NoClobber

docker compose -p $stack -f $compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'FAIL: Compose không hợp lệ.' }
docker compose -p $stack -f $compose up -d --wait --wait-timeout 90 postgres
if ($LASTEXITCODE -ne 0) { throw 'FAIL: Postgres riêng chưa healthy.' }
$endpoint = docker compose -p $stack -f $compose port postgres 5432
if ($LASTEXITCODE -ne 0 -or $endpoint -ne '127.0.0.1:55434') { throw 'FAIL: mapping DB khác dự kiến.' }
$dbContainer = docker compose -p $stack -f $compose ps -q postgres
if ($LASTEXITCODE -ne 0 -or -not $dbContainer) { throw 'FAIL: thiếu container DB riêng.' }
$mounts = docker inspect --format '{{json .Mounts}}' $dbContainer | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not ($mounts | Where-Object {
    $_.Type -eq 'volume' -and $_.Name -eq 'b333-preflight_pgdata' -and $_.Destination -eq '/var/lib/postgresql/data'
})) { throw 'FAIL: volume DB khác dự kiến.' }
$identity = docker compose -p $stack -f $compose exec -T postgres psql -X -U b333_test -d b333_preflight -At -v ON_ERROR_STOP=1 -c "select current_database() || ':' || current_user"
if ($LASTEXITCODE -ne 0 -or $identity -ne 'b333_preflight:b333_test') { throw 'FAIL: sai DB hoặc role.' }
'PASS: Postgres riêng healthy và đã xác minh port, volume, DB, role.'
```

### Terminal 2 — backend test

Mở PowerShell mới từ repo sau khi Terminal 1 PASS. Cấu hình trong `application.yml` dùng `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `MQTT_URL`, `MQTT_CLIENT_ID` và `SERVER_PORT`. Spring Boot có thể nhận override từ môi trường; các override bên dưới chỉ được gỡ trong terminal test. Giữ HTTP loopback, tắt MQTT control/config sync và dùng client ID backend riêng. [Backend config](../../backend/src/main/resources/application.yml)

```powershell
$ErrorActionPreference = 'Stop'
if (18083 -in [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port) {
    throw 'FAIL: cổng 18083 đã được sử dụng.'
}
Get-ChildItem Env: | Where-Object {
    $_.Name -match '^SPRING_' -or $_.Name -in @('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS')
} | ForEach-Object { Remove-Item -LiteralPath ("Env:" + $_.Name) }
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55434/b333_preflight'
$env:DB_USERNAME = 'b333_test'
$env:DB_PASSWORD = 'b333-test-local-only'
$env:SERVER_PORT = '18083'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:MQTT_ENABLED = 'true'
$env:MQTT_URL = 'tcp://127.0.0.1:1883'
$env:MQTT_CLIENT_ID = 'b333-preflight-backend'
$env:MQTT_USERNAME = ''
$env:MQTT_PASSWORD = ''
$env:CONTROL_COMMANDS_ENABLED = 'false'
$env:CONFIG_SYNC_ENABLED = 'false'
$env:JWT_SECRET = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$env:AI_SERVICE_KEY = [Guid]::NewGuid().ToString('N')
mvn -f backend/pom.xml spring-boot:run
if ($LASTEXITCODE -ne 0) { throw 'FAIL: backend test không chạy.' }
```

### Quay lại Terminal 1 — xác minh migration rồi tạo fixture

V2 tạo `houses`, `zones` và `devices`; ID cho phép cấp tường minh, và `online` có default. V10 thêm `camera_source_ok` nullable. V11 là migration mới nhất ở commit đã rà soát. Fixture phù hợp với cột bắt buộc hiện tại. Chỉ INSERT sau khi xác minh health, identity, schema và lịch sử Flyway. [V2](../../backend/src/main/resources/db/migration/V2__mvp_ingestion.sql) · [V10](../../backend/src/main/resources/db/migration/V10__camera_heartbeat.sql)

```powershell
if ((Invoke-RestMethod http://127.0.0.1:18083/actuator/health/readiness -TimeoutSec 5).status -ne 'UP') {
    throw 'FAIL: backend test chưa ready.'
}
$guard = @'
select current_database() = 'b333_preflight'
   and current_user = 'b333_test'
   and to_regclass('swiftletcare.houses') is not null
   and to_regclass('swiftletcare.zones') is not null
   and to_regclass('swiftletcare.devices') is not null
   and exists (select 1 from public.flyway_schema_history where version = '11' and success)
   and not exists (select 1 from public.flyway_schema_history where not success);
'@
$ready = $guard | docker compose -p $stack -f $compose exec -T postgres psql -X -U b333_test -d b333_preflight -At -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0 -or $ready -ne 't') { throw 'FAIL: DB, schema hoặc Flyway chưa đúng.' }

$fixture = @'
BEGIN;
DO $$ BEGIN
    IF current_database() <> 'b333_preflight' OR current_user <> 'b333_test' THEN
        RAISE EXCEPTION 'Wrong B3-33 database or role';
    END IF;
END $$;
INSERT INTO swiftletcare.houses(id,name,active) VALUES (1,'B3-33 test house',true);
INSERT INTO swiftletcare.zones(id,house_id,name,active) VALUES (1,1,'B3-33 test zone',true);
INSERT INTO swiftletcare.devices(zone_id,device_id,name,type,enabled)
VALUES (1,'ESP32-HOUSE01-ZONE01','B3-33 test ESP32','ESP32',true);
COMMIT;
'@
$fixture | docker compose -p $stack -f $compose exec -T postgres psql -X -U b333_test -d b333_preflight -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw 'FAIL: fixture lỗi hoặc đã tồn tại. Dừng để kiểm tra.' }

$verify = "select count(*) from swiftletcare.devices d join swiftletcare.zones z on z.id=d.zone_id join swiftletcare.houses h on h.id=z.house_id where h.id=1 and z.id=1 and h.active and z.active and d.device_id='ESP32-HOUSE01-ZONE01' and d.type='ESP32' and d.enabled"
$matched = docker compose -p $stack -f $compose exec -T postgres psql -X -U b333_test -d b333_preflight -At -v ON_ERROR_STOP=1 -c $verify
if ($LASTEXITCODE -ne 0 -or $matched -ne '1') { throw 'FAIL: fixture không khớp.' }
'PASS: backend ready, Flyway V11, House 1 / Zone 1 / ESP32 đúng.'
```

### Terminal 3 — build firmware staging có định danh

Ở HEAD `7e0006433400c22df1eda3445a2008f9205ba97e`, firmware local khớp commit và lời gọi `SENSOR_RECOVERED` sau ba lần đọc DHT tốt đã có trong commit. `git archive` chỉ lấy nội dung commit, nên bước dưới dừng nếu sau đó có firmware sửa local hoặc file untracked. Khi gặp điều kiện đó, đối chiếu delta trước khi chọn nguồn build. Build ngay trong staging, thêm cổng RFC2217/GDB ở bản sao và lưu SHA-256 của BIN/ELF. Firmware chưa tự báo hash runtime.

```powershell
$evidence = 'D:/swiftletcare-soak/b3-33'
$commit = git rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Không đọc được HEAD.' }
$firmwareChanges = git status --porcelain=v1 --untracked-files=all -- firmware
if ($LASTEXITCODE -ne 0 -or $firmwareChanges) { throw 'FAIL: git archive sẽ bỏ sót firmware local.' }
$tag = [Guid]::NewGuid().ToString('N')
$stage = Join-Path $env:TEMP "b333-preflight-$tag"
New-Item -ItemType Directory -Path $stage -ErrorAction Stop | Out-Null
$archive = Join-Path $stage 'firmware.zip'
git archive --format=zip -o $archive $commit firmware
if ($LASTEXITCODE -ne 0) { throw 'Git archive thất bại.' }
Expand-Archive -LiteralPath $archive -DestinationPath $stage
$project = Join-Path $stage 'firmware'
$stagedSketch = Join-Path $project 'src/sketch.ino'
if (-not (Select-String -LiteralPath $stagedSketch -SimpleMatch 'publishSensorEvent("SENSOR_RECOVERED", "DHT22", "THREE_GOOD_READINGS")' -Quiet)) {
    throw 'FAIL: staging thiếu nhánh SENSOR_RECOVERED đã rà soát.'
}
$wokwiConfig = Join-Path $project 'wokwi.toml'
if (Select-String -LiteralPath $wokwiConfig -Pattern '^\s*(rfc2217ServerPort|gdbServerPort)\s*=' -Quiet) {
    throw 'FAIL: staging đã có cấu hình cổng; kiểm tra trước khi thêm.'
}
Add-Content -LiteralPath $wokwiConfig -Encoding ascii -Value @('', 'rfc2217ServerPort = 4000', 'gdbServerPort = 3333')
pio run -d $project -e esp32dev
if ($LASTEXITCODE -ne 0) { throw 'Firmware build thất bại.' }
$bin = Join-Path $project '.pio/build/esp32dev/firmware.bin'
$elf = Join-Path $project '.pio/build/esp32dev/firmware.elf'
$binHash = (Get-FileHash -LiteralPath $bin -Algorithm SHA256 -ErrorAction Stop).Hash
$elfHash = (Get-FileHash -LiteralPath $elf -Algorithm SHA256 -ErrorAction Stop).Hash
[ordered]@{
    source_commit = $commit
    prepared_utc  = [DateTimeOffset]::UtcNow.ToString('o')
    wokwi_project = $project
    bin_path      = $bin
    bin_sha256    = $binHash
    elf_path      = $elf
    elf_sha256    = $elfHash
} | ConvertTo-Json | Out-File (Join-Path $evidence "preflight-firmware-$tag.json") -Encoding utf8 -NoClobber
code $project
```

### Terminal 4 và 5 — MQTT và Serial

Mở từ repo. Collector tạo file mới theo chế độ exclusive. Khi thử lại, dùng tên mới nhất quán trong các lệnh thu và audit. `received_at_utc` là thời điểm Python đọc bản ghi từ subscriber local, chịu độ trễ của pipe và lịch chạy tiến trình. Payload giữ nguyên timestamp thiết bị. [Mosquitto subscriber](https://mosquitto.org/man/mosquitto_sub-1.html)

```powershell
# Terminal 4, giữ collector MQTT chạy trước khi resume.
python -B scripts/capture-firmware-soak.py mqtt --mosquitto-sub 'C:/Program Files/mosquitto/mosquitto_sub.exe' --port 1883 --topic-base swiftletcare/1/1/ESP32-HOUSE01-ZONE01 --output D:/swiftletcare-soak/b3-33/mqtt-soak-new.jsonl
```

```powershell
# Terminal 5, trước lúc chọn Start Simulator and Wait for Debugger.
python -B -c "import serial,sys; print(sys.executable, serial.__version__)"
if ($LASTEXITCODE -ne 0) { throw 'FAIL: dùng đúng Python đã cài PySerial 3.5 cho cả hai lệnh.' }
$listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if (4000 -in $listeners.Port -or 3333 -in $listeners.Port) { throw 'FAIL: 4000 hoặc 3333 đang dùng.' }
python -B scripts/capture-firmware-soak.py serial --port 4000 --wait-seconds 120 --output D:/swiftletcare-soak/b3-33/serial-soak-new.log
```

### Wokwi và Terminal 6 — xác minh RFC2217 và broker trước khi bấm giờ

Trong cửa sổ VS Code của staging, bật Private IoT Gateway và chọn **Wokwi: Start Simulator and Wait for Debugger**. CPU chờ trước khi chạy. **PASS RFC2217** khi Terminal 5 báo `Serial attached`. Nếu quá thời gian hoặc attach lỗi, **FAIL** và kiểm tra cấu hình/cổng, chưa resume. Chạy GDB đúng toolchain từ terminal staging. Nếu PlatformIO dùng thư mục gói khác, dùng đường dẫn GDB thực tế.

```powershell
$gdb = Join-Path $env:USERPROFILE '.platformio/packages/toolchain-xtensa-esp32/bin/xtensa-esp32-elf-gdb.exe'
if (-not (Test-Path -LiteralPath $gdb)) { throw 'FAIL: chọn đúng GDB ESP32 của PlatformIO.' }
& $gdb ./.pio/build/esp32dev/firmware.elf
```

```text
target remote localhost:3333
continue
```

Ghi UTC Windows khi resume và giữ tab Wokwi hiển thị. Xác nhận `[BOOT]` thật xuất hiện ngay. Trước khi bấm giờ 3–5 phút, dùng cửa sổ kiểm tra riêng để đọc các bản tin mới. **PASS cùng broker** khi Wokwi báo CONNECTED, collector trên Windows `1883` thấy Telemetry mới và ACK `STORED` trùng ID, DB riêng có đúng ID, và Serial có `[BUFFER] Removed STORED` cùng ID. Nếu thiếu bất kỳ mắt xích nào hoặc Serial báo thêm boot, **FAIL** và dừng đúng phiên staging để điều tra. [Wokwi debugging](https://docs.wokwi.com/vscode/debugging) · [Wokwi RFC2217](https://docs.wokwi.com/vscode/project-config)

```powershell
# Cửa sổ kiểm tra riêng. Đọc các bản tin đã ghi, không publish probe.
$capture = 'D:/swiftletcare-soak/b3-33/mqtt-soak-new.jsonl'
Get-Content -LiteralPath $capture -Tail 20 | ForEach-Object {
    try {
        $record = $_ | ConvertFrom-Json -ErrorAction Stop
        $payload = $record.payload | ConvertFrom-Json -ErrorAction Stop
        if ($payload.event_id) {
            [pscustomobject]@{ UTC = $record.received_at_utc; Topic = $record.topic; Id = $payload.event_id; Status = $payload.status }
        }
    } catch { Write-Warning 'Dòng chưa đọc được; kiểm tra lại sau khi collector ghi xong.' }
}
# Terminal 1, thay bằng ID Telemetry đã ghép với ACK.
$eventId = '<event_id>'
if ($eventId -notmatch '^ESP32-HOUSE01-ZONE01-[0-9a-f]+-[0-9]+$') { throw 'FAIL: hãy nhập ID firmware đã quan sát.' }
$stored = docker compose -p $stack -f $compose exec -T postgres psql -X -U b333_test -d b333_preflight -At -v ON_ERROR_STOP=1 -c "select count(*) from swiftletcare.telemetry t join swiftletcare.devices d on d.id=t.device_id where d.device_id='ESP32-HOUSE01-ZONE01' and t.event_id='$eventId'"
if ($LASTEXITCODE -ne 0 -or $stored -ne '1') { throw 'FAIL: ID chưa được xác minh trong DB test.' }
if (-not (Select-String -LiteralPath D:/swiftletcare-soak/b3-33/serial-soak-new.log -SimpleMatch "[BUFFER] Removed STORED: $eventId |" -Quiet)) {
    throw 'FAIL: chưa thấy firmware giải phóng ID đã ACK.'
}
'PASS: ID trong DB riêng và Serial xác nhận đã xử lý ACK.'
```

### Checklist pre-flight 3–5 phút

- [ ] Backend readiness UP, fixture khớp và ACK end-to-end xác nhận cùng broker trước khi bấm giờ. Dùng log CONNECT/SUBSCRIBE của service nếu logging hiện tại có bật.
- [ ] State/Serial ghi AUTO, Serial có `[MQTT] CONNECTED` và SNTP sync.
- [ ] Dùng `Get-Item ... | Select-Object Length,LastWriteTimeUtc` hai lần để xác nhận các file đang tăng. Serial có `[BOOT]`, reset reason và ít nhất hai checkpoint `[RESOURCE]` với uptime tăng, free/min heap, connects/attempts, buffer và dropped.
- [ ] Telemetry tiếp tục có ID mới. Ghép ACK `STORED` cùng ID và tìm `[BUFFER] Removed STORED` cùng ID trong Serial. Kiểm tra buffer không tiến tới 120 và so bộ đếm dropped/reconnect giữa các checkpoint.
- [ ] Xác nhận chỉ có boot chủ đích ở đầu, kiểm tra marker, uptime và State boot ID. Nếu có thêm boot ngoài ý muốn, giữ reset reason và log để ghi lỗi/tái hiện theo Jira.
- [ ] Gõ `TEST STATUS` ở đầu/cuối, ghi mốc UTC Windows. Đối chiếu hash BIN và ELF với manifest.
- [ ] Sau 3–5 phút dừng đúng phiên test đã khởi tạo và hai collector. Chạy audit, kiểm tra hai dãy khoảng Heartbeat và evidence. Chỉ lên lịch lượt 120 phút riêng sau khi pre-flight đạt.

```powershell
Select-String -LiteralPath D:/swiftletcare-soak/b3-33/serial-soak-new.log -Pattern '\[BOOT\]|\[RESOURCE\]|\[MQTT\] CONNECTED|\[BUFFER\] Removed STORED|\[TEST\] utc='
python -B scripts/audit-firmware-soak.py --serial D:/swiftletcare-soak/b3-33/serial-soak-new.log --mqtt D:/swiftletcare-soak/b3-33/mqtt-soak-new.jsonl --topic-base swiftletcare/1/1/ESP32-HOUSE01-ZONE01 --output D:/swiftletcare-soak/b3-33/preflight-analysis.json

# Trong terminal giữ biến manifest ở bước build.
if ((Get-FileHash -LiteralPath $bin -Algorithm SHA256).Hash -ne $binHash -or
    (Get-FileHash -LiteralPath $elf -Algorithm SHA256).Hash -ne $elfHash) {
    throw 'Hash artefact thay đổi trong lượt chạy.'
}
```

### Kiểm thử thực tế

- `python -B -m unittest discover -s scripts -p 'test_*firmware_soak.py' -v` đạt 10 test, gồm hai đồng hồ, byte Serial, ngắt kết nối và bảo vệ file đích.
- Smoke test Mosquitto Windows 2.1.2 trên broker loopback riêng ghi UTC và payload QA khi collector còn chạy. Lần thử đầu xác định `%U` không được hỗ trợ trên Windows. Bản đã sửa lấy UTC trực tiếp ở Python. Smoke test nằm ngoài bằng chứng soak.
- Đối chiếu offline toàn bộ log thật xác nhận 120 ID, 0 biến thể payload theo ID và 0 lỗi schema/dải đo. Hash log cũ giữ nguyên.
- Rà soát môi trường Windows xác nhận Docker Desktop và Compose v5.6.1 truy cập được, các container hiện hữu dùng cổng như bảng kiểm tra, và Mosquitto Windows trả CONNACK/SUBACK. Cổng 55434, 18083, 4000 và 3333 không có TCP listener lúc kiểm tra.
- `docker compose config` xác nhận Compose có một dịch vụ Postgres, mapping loopback 55434 và volume riêng. Project, volume và network mới chưa tồn tại lúc kiểm tra. Mười khối PowerShell qua parser. Kiểm tra nội dung ZIP `git archive` xác nhận sketch khớp working tree sau chuẩn hóa xuống dòng và có `SENSOR_RECOVERED`.
- Người vận hành đã cài PySerial 3.5. Python của phiên rà soát không import được module đó; kiểm tra đúng interpreter trong Terminal 5. Chưa tạo Postgres mới, chưa chạy INSERT, Wokwi/RFC2217 hoặc ACK end-to-end. B3-33 vẫn PARTIAL.

## Lượt chính thức — chuẩn bị ngày 26/11/2026

**B3-33 PARTIAL.** Đã build và chuẩn bị phiên mới. Mốc resume/start/end và hai capture của phiên mới còn chờ thao tác Wokwi. Đánh giá dựa trên tiêu chí Jira B3-33 “Wokwi liên tục ≥120 phút với MQTT/Telemetry, quan sát heap và reconnect/reset, không có reset ngoài ý muốn; nếu có thì ghi lỗi và tái hiện”. Ngưỡng cảnh báo collector chỉ hỗ trợ nhận biết thiếu dữ liệu.

### Môi trường và bằng chứng đã kiểm tra

| Thành phần | Kết quả quan sát thực tế |
| --- | --- |
| Git và nguồn firmware | `AI-b3`, HEAD `7e0006433400c22df1eda3445a2008f9205ba97e`. Dưới `firmware/` chỉ thấy thay đổi local `wokwi.toml`, thêm RFC2217 4000 và GDB 3333. Build trong Git archive riêng, sao chép cấu hình Wokwi vào staging. |
| Backend và PostgreSQL | Readiness `UP` ở `127.0.0.1:18083`. Kiểm tra chỉ đọc đúng container `b333-preflight-postgres` xác nhận loopback `55434`, DB `build2_test`, role `build2`, Flyway rank 11 thành công. Đây là instance B3-33, tách cổng `55433` của Build 2. |
| Broker | Mosquitto Windows PID `5296` trên `127.0.0.1:1883`. Probe Paho chỉ subscribe, nhận 5 SUBACK và 1 Heartbeat thật. Firmware trỏ `host.wokwi.internal:1883`. |
| Wokwi phiên cũ | Cổng 4000/3333 thuộc `Code.exe` PID `39024`. GDB PID `38216`. Serial pre-flight PID Python `3408` (wrapper `27208`), MQTT pre-flight `14748` (child Mosquitto `17064`). Collector MQTT mở tên `mqtt-soak-official-20261126-220005.jsonl` có Python `14796` và child `14412` lúc kiểm tra. PID có thể thay đổi. File tên official cũ thiếu nguồn Serial/timeline tương ứng và được giữ riêng. |
| Python | `.venv/Scripts/python.exe`, Python 3.14.6, PySerial 3.5, Paho MQTT 2.1.0. |

Workspace BIN có SHA-256 `D07ABA72F21D1B19B0C08BC09AC40FFAF5F20844D692CD1F2632A25E6BF3A4CB`, ELF `BD8DEDDEC4526737C6BDBDA4C56BC833C20C27CCB454529CE69906FF02ADBCFA`. Chưa xác minh phiên VS Code cũ đã nạp artefact nào. Mở Wokwi từ đúng staging đã build sau khi dừng phiên cũ.

| Artefact staging esp32dev | SHA-256 |
| --- | --- |
| BIN | `FD5D42C44055F3C3BB0346B81482EE698B69D4D7468F520CA6D9288496C9B30D` |
| ELF | `96ED22174BE25EF77803454D5FD1FD7163B6F085959FA94BC6E25605A2BBF76B` |

PlatformIO báo SUCCESS, RAM 56.136 byte (17,1%), flash 837.465 byte (63,9%). Diagram build dùng ESP32 DevKitC v4, DHT22, quang trở, biến trở và bốn LED. Cấu hình thực tế của staging được ghi trong manifest. Phiên bản firmware truy xuất theo Git SHA của build staging, không có cơ chế hash tự báo từ firmware.

### Audit pre-flight thực tế

Bản `D:/swiftletcare-soak/b3-33/preflight-audit-review-20261126-220919.json` phân tích phần log có sẵn lúc đọc. File nguồn có thể tiếp tục tăng nếu collector cũ còn chạy. Hai capture pre-flight không đồng thời hoàn toàn.

| Chỉ số | Kết quả |
| --- | --- |
| Serial | 13 RESOURCE, uptime từ 2.880.216 đến 3.600.280 ms, span 720.064 ms. **MISSING EVIDENCE** cho BOOT/reset reason. |
| Free/min heap | Free heap 205.776 → 205.772 byte, min/max 205.772/205.776. Minimum heap báo 196.540 byte. |
| Buffer và TX/RX | Buffer 0/120, peak 1, dropped/TX fail/TX queue drop/RX drop/event drop đều 0. Connects/attempts 3/3 ở cả hai đầu checkpoint. Loop max 139 ms. |
| MQTT | 408 Heartbeat, 164 Telemetry, 164 ACK, 165 State AUTO, 0 Events. 164/164 ID khớp STORED, 156 ID khớp thêm Serial removal. |
| Gián đoạn | Span timestamp Heartbeat thiết bị 817 giây. Span UTC phía nhận 1.363,906 giây, khoảng lớn nhất 289,693 giây. Chưa xác định nguyên nhân. |

### Phiên đã chuẩn bị và thứ tự terminal

Dừng **đúng phiên cũ** qua giao diện Wokwi và terminal GDB của phiên đó. Xác minh đúng đường dẫn log rồi nhấn Ctrl+C riêng trong các terminal collector cũ. Nếu phát hiện phiên hợp lệ khác đang chạy, giữ phiên đó và thực hiện audit nguồn evidence trước khi chọn phiên mới. Giữ Windows Mosquitto, Backend và mọi container đang chạy. Recheck `4000/3333` đã rảnh trước khi mở staging. Giữ cửa sổ simulator hiện và tránh Windows sleep trong lượt đo.

Chạy khối biến sau trong mỗi PowerShell terminal mở tại repo.

```powershell
Set-Location D:/New_folder/System-for-Swiftlet-Farming
$py = (Resolve-Path .venv/Scripts/python.exe).Path
$session = 'D:/swiftletcare-soak/b3-33/official-20261126-220919-2c0878a4'
$meta = Get-Content (Join-Path $session 'manifest.json') -Raw | ConvertFrom-Json
$project = $meta.project
$topic = $meta.topic_base
```

**Terminal 1 — xác minh và mở staging.**

```powershell
$readiness = Invoke-RestMethod 'http://127.0.0.1:18083/actuator/health/readiness' -TimeoutSec 5
if ($readiness.status -ne 'UP') { throw 'Backend test is not ready.' }
$listen = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
if (4000 -in $listen.Port -or 3333 -in $listen.Port) { throw 'Stop the previous Wokwi session manually first.' }
if ((Get-FileHash -LiteralPath $meta.artifacts.firmware.path -Algorithm SHA256).Hash -ne $meta.artifacts.firmware.sha256 -or
    (Get-FileHash -LiteralPath $meta.artifacts.elf.path -Algorithm SHA256).Hash -ne $meta.artifacts.elf.sha256) {
    throw 'Firmware artifacts differ from the manifest.'
}
code -n $project
```

Trong cửa sổ staging, bật Private IoT Gateway. Chờ Terminal 2 báo `mqtt subscribed` với đủ 5 mã QoS, rồi chọn **Wokwi: Start Simulator and Wait for Debugger**. Xác nhận cửa sổ staging và cấu hình đúng. CPU giữ pause trong khi attach Serial.

**Terminal 2 — MQTT.** Bỏ `--count` cho lượt chính thức. Idle 180 giây là cảnh báo lỗi collector cho toàn bộ traffic, dùng thời gian này để hoàn thành thao tác Wokwi/GDB. Nếu quá hạn, bảo toàn thư mục và chuẩn bị phiên mới.

```powershell
& $py -B scripts/capture-firmware-soak.py mqtt --driver paho --port 1883 --topic-base $topic --idle-seconds 180 --output (Join-Path $session 'mqtt-soak.jsonl') --status (Join-Path $session 'mqtt-status.jsonl')
```

**Terminal 3 — Serial**, sau khi Wokwi mở listener và trước khi CPU chạy.

```powershell
& $py -B scripts/capture-firmware-soak.py serial --port 4000 --wait-seconds 120 --output (Join-Path $session 'serial-soak.log') --status (Join-Path $session 'serial-status.jsonl')
```

**Terminal 4 — GDB.** Chờ `Serial attached` trước khi attach GDB.

```powershell
$gdb = Join-Path $env:USERPROFILE '.platformio/packages/toolchain-xtensa-esp32/bin/xtensa-esp32-elf-gdb.exe'
& $gdb $meta.artifacts.elf.path
```

Trong GDB chạy `target remote 127.0.0.1:3333`. Ngay trước khi nhập `continue`, ghi mốc resume trong Terminal 5. Mốc này là thời điểm thao tác phía máy thu, sau đó đối chiếu với UTC của BOOT trong journal. Giữ GDB và Wokwi mở.

**Terminal 5 — mốc và giám sát.**

```powershell
& $py -B scripts/manage-firmware-soak.py mark resume 'GDB connected, entering continue immediately' --session $session
if ($LASTEXITCODE) { throw 'Resume gate failed. Keep CPU paused.' }
# Enter continue in GDB now.
```

Xác minh `[BOOT]` có numeric reset reason, uptime bắt đầu gần 0 (RESOURCE đầu thường sau khoảng 60 giây mô phỏng), State AUTO, Heartbeat và một ID khớp qua Telemetry → STORED → `[BUFFER] Removed STORED`. Nếu thiếu BOOT hoặc uptime đầu đến từ phiên cũ, dừng lượt đó. Có thể gõ `TEST STATUS` trong Serial Monitor để đối chiếu UTC thiết bị và uptime. Sau khi đủ bằng chứng, ghi mốc bắt đầu. Các mốc chỉ ghi một lần.

```powershell
Select-String -LiteralPath (Join-Path $session 'serial-soak.log') -Pattern '\[BOOT\]|\[RESOURCE\]|\[BUFFER\] Removed STORED' | Select-Object -First 20
Get-Content -LiteralPath (Join-Path $session 'mqtt-soak.jsonl') -Tail 20
& $py -B scripts/manage-firmware-soak.py mark start 'Confirmed initial BOOT, AUTO and a matching telemetry/STORED/Serial ID' --session $session
if ($LASTEXITCODE) { throw 'Start gate failed.' }
while ($true) {
    & $py -B scripts/manage-firmware-soak.py monitor --session $session
    Start-Sleep -Seconds 30
}
```

Dừng vòng monitor bằng Ctrl+C riêng ở Terminal 5. Mỗi collector in PID và số byte, ghi journal định kỳ và báo lỗi nếu mất transport. Dùng `Get-Process -Id <pid>` nếu journal stale. Cảnh báo Heartbeat age >30 giây hoặc RESOURCE age >90 giây là mốc chẩn đoán. Nếu pause/resume, reboot, reconnect hoặc collector lỗi, ghi sự kiện UTC và nguyên nhân quan sát được. Dùng `mark pause`, `mark resume-after-pause` hoặc `mark note` với mô tả. Giữ nguyên log; không nối hai khoảng bị ngắt để đủ thời gian. Có reset ngoài ý muốn thì lưu reset reason, mốc UTC, ngữ cảnh log và bước tái hiện để mở bug.

Chỉ kết thúc lượt dự kiến khi `wall_seconds ≥7200`, `resource_wall_seconds ≥7200` và `resource_uptime_ms ≥7200000` giữa các RESOURCE **nằm trong** khung start/end. Do checkpoint khoảng 60 giây, mốc này có thể cần hơn 120 phút từ lúc bấm start. Đối chiếu Heartbeat, RESOURCE, ACK và collector còn liên tục trước khi ghi kết thúc.

```powershell
& $py -B scripts/manage-firmware-soak.py mark end 'Recorded final RESOURCE and checked MQTT before stopping the collectors' --session $session
if ($LASTEXITCODE) { throw 'End milestone failed.' }
```

Sau mốc end, Ctrl+C ở Terminal 2 và 3 trước khi dừng simulator để journal có `operator_stop`. Chạy audit sau khi file đã đóng. Với lượt bị gián đoạn, ghi nguyên nhân và vẫn lưu audit của phần dữ liệu thu được. Chỉ dùng tên output mới.

```powershell
& $py -B scripts/audit-firmware-soak.py --serial (Join-Path $session 'serial-soak.log') --mqtt (Join-Path $session 'mqtt-soak.jsonl') --topic-base $topic --serial-status (Join-Path $session 'serial-status.jsonl') --mqtt-status (Join-Path $session 'mqtt-status.jsonl') --output (Join-Path $session 'audit-result.json')
if ($LASTEXITCODE) { throw 'Audit failed; retain raw logs.' }
& $py -B scripts/manage-firmware-soak.py finalize --session $session
```

Finalize điền UTC, timeline, tình trạng collector, duration và review findings vào manifest, rồi lưu `soak-summary.md`. Auditor báo heap đầu/cuối/min/max và chuỗi heap, boot marker/reason, Resource counters, ACK và giao ba nguồn, mode/boot ID, gián đoạn và clock regression. Công cụ để trạng thái `REQUIRES REVIEW`. Reviewer chấm theo Jira sau khi kiểm tra raw log, mọi khoảng gián đoạn và bằng chứng reset, không lấy khoảng timestamp Heartbeat hoặc uptime tích lũy làm thời lượng soak.

### Kiểm thử công cụ

- `.venv/Scripts/python.exe -B -m unittest discover -s scripts -p 'test_*firmware_soak.py' -v` đạt 16 test.
- Probe Windows thực tế bằng Paho xác nhận 5 SUBACK, ghi 1 Heartbeat thật có UTC/monotonic và kết thúc finite probe với exit 0. Chẩn đoán mosquitto CLI trong pipe cho thấy SUBACK debug có thể bị đệm khi thiếu payload; runbook chính thức dùng Paho để chốt readiness trước resume.
- Probe chỉ subscribe một nhóm topic QA rỗng trên cùng broker nhận 5 SUBACK rồi báo lỗi idle và exit 1 như dự kiến. Không publish probe. Chín khối PowerShell của runbook qua kiểm tra cú pháp.
- Thư mục chính thức đã tạo, gồm manifest staging và timeline chưa có sự kiện. Chưa resume Wokwi mới, chưa đo 120 phút và chưa có raw log hoặc audit chính thức.
