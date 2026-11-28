# B3-22 — NFR v2

Lượt 26/11/2026 dùng Windows 11 build 26200, Intel 12 logical CPU, Python 3.14.6, Java 21, PostgreSQL 17.11 và Mosquitto 2.1.2 trên Docker Desktop loopback. PostgreSQL `55435/p3_nfr`, MQTT `51885`, backend `18084`. Broker phục vụ fixture QA. Lượt tải đầu trùng một phần thời gian chạy suite tích hợp khác trên cùng broker; backend của hai lượt dùng DB riêng. Các lần đo Alert dùng cùng host làm nguồn UTC. MQTT/HTTP RTT dùng đồng hồ monotonic.

| Nhóm đo | Mẫu | p50 ms | p95 ms | Max ms | Thiếu hoặc lỗi | Ngưỡng / trạng thái |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| Mẫu Telemetry thứ nhất → backend tạo ENV_THRESHOLD | 5/5 | 2.025,12 | 2.084,52 | 2.084,52 | 0 | Đã đo, dùng cadence 1 giây giữa mẫu |
| Mẫu thứ ba → backend tạo ENV_THRESHOLD | 5/5 | 20,28 | 77,49 | 77,49 | 0 | Đã đo, tách khỏi ngưỡng DOM |
| Backend tạo → House WebSocket nhận | 5/5 | 29,30 | 49,04 | 49,04 | 0 | Đã đo, chưa có ngưỡng WS riêng |
| Backend tạo → React DOM | 0/5 | — | — | — | 5 mốc DOM chưa đo | ≤5.000 ms, **NOT TESTED**. P3-UI-02. |
| MQTT, 50 ID × 3 mẫu gửi dồn → STORED | 20/150 | 3.527,32 | 6.878,36 | 6.949,07 | 130 timeout, 0 publish error; DB commit 150/150 | **FAIL** cho ACK đầy đủ, P3-MQTT-01. |
| MQTT lặp với backend staging ghi mã lỗi | 20/150 | 3.474,36 | 5.879,16 | 5.897,53 | 130 timeout; 150 commit; 130 lỗi Paho 32202 | **FAIL**, xác nhận `REASON_CODE_MAX_INFLIGHT`. |
| MQTT, 50 kết nối độc lập → subscriber thấy Telemetry | 150/150 | 9,83 | 16,06 | 18,73 | 0 thiếu | Đã đo; cùng burst, 50 CONNACK. |
| MQTT, 50 kết nối độc lập → STORED | 20/150 | 5.217,53 | 8.126,85 | 8.161,20 | 130 timeout; 150 commit | **FAIL**. Chạy sau khi Maven đã kết thúc. |
| Authorization HTTP | 3/3 | — | — | — | 0 | **PASS**. Chủ House 200, JWT hết hạn 401, JWT House khác 403. |
| Upload JPEG mẫu 17.423 byte qua API ảnh | 10/10 | 35,50 | 159,13 | 159,13 | 0 timeout, 10 HTTP 201; body multipart 17.688 byte/mẫu | Đã đo, Jira chưa chốt ngưỡng. |
| Video AI | 0 | — | — | — | Chưa có observer/video pipeline QA | **NOT TESTED**, P2/P3. |

Status B3-22 **FAIL** cho lượt tải, tiêu chí React DOM còn **NOT TESTED**. P95 dùng nearest rank; giá trị MQTT chỉ tính trên ACK thực nhận. Hai phép đo phát hiện lấy mốc lúc publisher bắt đầu gửi, gồm hành trình MQTT đến backend. `backend_created_ms` đến từ `data.createdAt` trên sự kiện Alert, timestamp WS lấy ngay tại thread nhận frame. Hai lần gọi công cụ ảnh ban đầu dừng vì thiếu import `urlopen`, trước khi ghi mẫu HTTP. Lượt cuối đo sau khi sửa import. Lượt chẩn đoán dùng bản sao backend chỉ thêm mã lỗi vào log, và HTTP ảnh chạy trên cổng `18085`. JPEG dự án dùng để đo upload; video cần phép đo riêng.

## Tái hiện

Sử dụng container dự án P3 riêng từ [hướng dẫn integration](build3-p3-integration.md). Tạo thêm DB trống `p3_nfr` trong **Postgres P3 riêng**, dùng backend test bind loopback với `MQTT_URL=tcp://127.0.0.1:51885`, `DB_URL=jdbc:postgresql://127.0.0.1:55435/p3_nfr` và `-Duser.timezone=UTC`. Sau readiness và Flyway, nạp [fixture SQL](build3-p3-nfr-fixture.sql) vào đúng DB và lưu ID trả về. Cài `paho-mqtt==2.1.0` và `websocket-client==1.8.0` trong môi trường Python test. Tạo JWT QA cho owner/foreign từ các user ID fixture bằng signing key của backend QA và JWT expired có thời điểm hết hạn trong quá khứ. Đặt chúng vào `P3_OWNER_JWT`, `P3_FOREIGN_JWT`, `P3_EXPIRED_JWT`. Đặt `P3_AI_SERVICE_KEY` bằng service key **test**. Dùng thư mục output mới cho mỗi phép đo.

```powershell
$db = 'swiftletcare-p3-qa-db-20261126'
$identity = docker exec $db psql -XAt -U p3qa -d p3_qa -c "select current_database()||':'||current_user"
if ($LASTEXITCODE -ne 0 -or $identity -ne 'p3_qa:p3qa') { throw 'Wrong QA DB or role.' }
docker exec $db psql -X -U p3qa -d p3_qa -v ON_ERROR_STOP=1 -c 'create database p3_nfr'
if ($LASTEXITCODE -ne 0) { throw 'Check existing p3_nfr before reusing. Leave existing data intact.' }
# Terminal backend, after confirming the QA DB and broker.
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55435/p3_nfr'
$env:DB_USERNAME = 'p3qa'
$env:DB_PASSWORD = 'p3-local-fixture'
$env:MQTT_ENABLED = 'true'
$env:MQTT_URL = 'tcp://127.0.0.1:51885'
$env:MQTT_CLIENT_ID = 'b3p3-nfr-qa'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '18084'
$env:JWT_SECRET = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$env:AI_SERVICE_KEY = [Guid]::NewGuid().ToString('N')
java '-Duser.timezone=UTC' -jar $qaJar
# In the fixture terminal, after readiness UP. Use the same dedicated container name.
Get-Content -Raw docs/tests/build3-p3-nfr-fixture.sql | docker exec -i swiftletcare-p3-qa-db-20261126 psql -X -U p3qa -d p3_nfr -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw 'QA fixture failed. Inspect the output.' }
```

```powershell
python -B scripts/measure-build3.py --output $loadEvidence mqtt-load --house $house --zone $loadZone --port 51885 --cycles 3 --timeout 20 --individual-clients --test-broker-confirmed
python -B scripts/measure-build3.py --output $authEvidence authorization --house $house --port 18084
python -B scripts/measure-build3.py --output $alertEvidence alert-live --house $house --port 18084 --mqtt-port 51885 --device '2:B3P3-ALERT-1' --device '3:B3P3-ALERT-2' --device '4:B3P3-ALERT-3' --device '5:B3P3-ALERT-4' --device '6:B3P3-ALERT-5' --test-broker-confirmed
python -B scripts/measure-build3.py --output $imageEvidence snapshots --port 18084 --camera $camera --jpeg ai/samples/predator/owl01-crop.jpg --test-backend-confirmed
```

Thay các cặp `ZONE:DEVICE` bằng ID fixture. Mỗi Alert dùng Zone mới để tránh cooldown. Sau khi P5 tích hợp live, yêu cầu DOM của một Alert hiển thị thuộc tính `data-alert-id` khớp Backend. Trước khi gửi Telemetry, chạy observer dưới đây trong console Chromium trên trang Alerts đang mở. Chỉ áp dụng cho phiên QA đã đăng nhập đúng House.

```javascript
window.p3DomSamples = new Map()
const observer = new MutationObserver(() => {
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (document.hidden) return
    for (const row of document.querySelectorAll('[data-alert-id]')) {
      const id = row.dataset.alertId
      if (id && row.getClientRects().length && !window.p3DomSamples.has(id)) {
        window.p3DomSamples.set(id, { id, dom_rendered_ms: Date.now() })
      }
    }
  }))
})
observer.observe(document.body, { childList: true, subtree: true, attributes: true })
```

Sau khi đo, dùng `copy([...window.p3DomSamples.values()].map(row => JSON.stringify(row)).join('\n'))` để lưu JSONL vào file mới và ngắt observer. Chạy `python -B scripts/measure-build3.py --output $correlation alert-latency --input "$alertEvidence/events.jsonl" --dom-input $domJsonl --expected 5`. Hai nguồn phải dùng clock UTC cùng máy hoặc có offset đo được. Lưu log/trace gốc cùng JSONL. Thu video phải ghi độ phân giải, FPS, nguồn, thời lượng và lưu lượng trước khi đánh giá.

[Raw MQTT](evidence/build3-p3-20261126/mqtt-load/events.jsonl) · [DB count](evidence/build3-p3-20261126/mqtt-load/database-count.txt) · [Mã lỗi MQTT](evidence/build3-p3-20261126/mqtt-load-diagnostic/diagnostic.txt) · [50 kết nối MQTT](evidence/build3-p3-20261126/mqtt-load-50-clients/result.json) · [Raw Alert](evidence/build3-p3-20261126/alerts/events.jsonl) · [HTTP auth](evidence/build3-p3-20261126/authorization/events.jsonl) · [Raw ảnh](evidence/build3-p3-20261126/snapshots-final/events.jsonl) · [Bug tracker](bug-tracker.md)

## P3-MQTT-01 — phân tích và thiết kế bản sửa

Trong lượt trước sửa với 50 publisher và 150 bản tin, PostgreSQL commit đủ 150; outbound `publish` ném 130 lần mã Paho `32202` và 130 ID thiếu ACK. Đây là từ chối trước khi ACK được chấp nhận vào hàng đợi publish của Paho. `Ingestion.store` hoàn thành transaction trước khi gateway tạo ACK, kể cả trường hợp replay cùng nội dung. Callback broker xử lý cả incoming Telemetry và completion. Khi burst lấp hàng đợi nhận, luồng đọc MQTT có thể chờ slot xử lý incoming trước khi đọc PUBACK ở phía sau. Khi đủ 10 publish đang in-flight theo mặc định Paho, lần gọi trực tiếp từ callback ném lỗi. Gateway cũ chỉ log ngoại lệ và không lưu ACK đó để thử lại. [ClientState Paho 1.2.5](https://github.com/eclipse-paho/paho.mqtt.java/blob/v1.2.5/org.eclipse.paho.client.mqttv3/src/main/java/org/eclipse/paho/client/mqttv3/internal/ClientState.java) · [CommsCallback Paho 1.2.5](https://github.com/eclipse-paho/paho.mqtt.java/blob/v1.2.5/org.eclipse.paho.client.mqttv3/src/main/java/org/eclipse/paho/client/mqttv3/internal/CommsCallback.java) · [CommsReceiver](https://github.com/eclipse-paho/paho.mqtt.java/blob/v1.2.5/org.eclipse.paho.client.mqttv3/src/main/java/org/eclipse/paho/client/mqttv3/internal/CommsReceiver.java) · [Giới hạn mặc định](https://github.com/eclipse-paho/paho.mqtt.java/blob/v1.2.5/org.eclipse.paho.client.mqttv3/src/main/java/org/eclipse/paho/client/mqttv3/MqttConnectOptions.java)

Gateway vẫn dùng QoS 1, cùng topic và payload. Sau khi xác minh commit, callback đưa ACK vào hàng đợi RAM mặc định 512 mục (gồm mục đang gửi) rồi tiếp tục xử lý. Một worker gửi tối đa một Telemetry ACK chưa hoàn tất. `published` nghĩa là Paho đã nhận lệnh publish. `confirmed` nghĩa là callback thành công sau PUBACK của broker. Việc firmware hoặc collector nhận ACK được đối chiếu riêng.

Worker xử lý từ chối publish hoặc token báo thất bại với tối đa 4 lần thử và backoff 100/200/400 ms, giữ mục trong hàng đợi khi kết nối offline. Token còn chờ PUBACK sau 10 giây được cảnh báo và tiếp tục theo dõi. Khi đầy hoặc hết lượt thử, worker ghi drop để firmware retry. Các replay hợp lệ đang chờ cùng topic/ID được gộp; replay sau confirm có thể nhận ACK mới. Khi shutdown, ngừng nhận ACK mới và cho worker tối đa 3 giây để drain. Hàng đợi volatile nên crash hoặc hết giờ drain vẫn cần firmware retry. QoS 1 có thể phát lại nếu kết quả một lần gửi bị mất lúc reconnect.

Các counter dùng meter `swiftletcare.mqtt.telemetry.ack` với tag `stage` gồm `db_committed`, `db_verified_replay`, `queued`, `published`, `confirmed`, `coalesced`, `retry`, `dropped` và `slow_confirmation`. Không dùng event ID làm metric label.

### Kết quả QA sau sửa ngày 26/11/2026

Chạy sau Maven verify, trên PostgreSQL `127.0.0.1:55435/p3_nfr`, broker QA `127.0.0.1:51885` và backend loopback `18085`. Cùng 50 kết nối, 3 vòng gửi dồn và timeout 20 giây. Collector ghi riêng ACK lặp, quan sát thêm 1 giây sau ACK cuối để kiểm tra bản lặp; khoảng quan sát này không gia hạn deadline ACK đầu.

| Chỉ số | Trước sửa | Sau sửa |
| --- | ---: | ---: |
| Telemetry quan sát ở subscriber | 150/150 | 150/150 |
| PostgreSQL có đúng event_id | 150/150 | 150/150 |
| ACK STORED khớp event_id | 20/150 | 150/150 |
| Timeout | 130 | 0 |
| ACK lặp / ACK thiếu commit | Không đối chiếu riêng | 0 / 0 |
| Lỗi Paho 32202 | 130 | 0 trong log tải |
| ACK p50 / p95 / max (ms) | 5.217,53 / 8.126,85 / 8.161,20 (20 mẫu) | 9.890,63 / 11.370,62 / 11.526,88 (150 mẫu) |

Delta metric `db_committed → queued → published → confirmed` đều là 150. Replay, coalesced, retry, dropped và slow confirmation đều bằng 0. Hai phân phối latency có số mẫu khác nhau. Trong lượt trước, 130 mẫu timeout không tham gia percentile. Kết quả sau sửa chứng minh đủ ACK trong deadline 20 giây của bài tải.

Maven verify UTC/INFO đạt **85 test, 0 fail, 0 error, 0 skip**. Integration Ingestion đạt 14/14, gồm 150 ACK dưới burst nhiều sender, DB rollback không ACK, và replay hợp lệ. Ba unit test xác minh chỉ một token chưa hoàn tất, giới hạn queue, coalescing, backoff, xử lý reconnect và hết ngân sách retry. Command/ACK, Config ACK và Alert Engine/Workflow đều qua regression.

**P3-MQTT-01 PASS trên QA. B3-22 PARTIAL.** React DOM ≤5 giây và video vẫn NOT TESTED.

[So tập event_id và metrics](evidence/build3-p3-20261126/mqtt-ack-fix/comparison.json) · [Kết quả tải](evidence/build3-p3-20261126/mqtt-ack-fix/load/result.json) · [Raw MQTT](evidence/build3-p3-20261126/mqtt-ack-fix/load/events.jsonl) · [PostgreSQL IDs](evidence/build3-p3-20261126/mqtt-ack-fix/postgres-event-ids.txt) · [Gateway log](evidence/build3-p3-20261126/mqtt-ack-fix/backend.log) · [Maven log](evidence/build3-p3-20261126/mqtt-ack-fix/maven-verify.log)

## Current integration regression

- 50 individual clients, 150 published, 150 rows in PostgreSQL and 150 STORED ACKs matched exactly by event_id. No timeout, duplicate ACK or publish error. Broker receive p50/p95: 5.47/9.53 ms. STORED receive p50/p95: 6,013.75/7,447.29 ms, max 7,596.72 ms. This was a shared QA host with concurrent test jobs and a 20 s collector deadline. The ACK result passes completeness for this load, while the latency is a recorded risk. [Load evidence](evidence/build3-completion-load-20261127/result.json) ? [DB reconciliation](evidence/build3-completion-load-20261127/reconciliation.json)
- Five isolated real Edge PREDATOR samples from Backend createdAt to first matching React DOM row: 106, 70, 82, 72, 88 ms. Linear interpolation p50 82 ms, p95 102.4 ms, max 106 ms. Same host clock, one worker, scoped House socket and REST verified by Alert ID. Each browser run verified snapshot display and invalid-token HTTP 401. This measures PREDATOR delivery only. [Browser log](evidence/build3-completion-alert-dom-20261127.txt)

Overall B3-22 remains PARTIAL. Current device-to-DOM alert coverage, native notifications, video bandwidth and a representative load/latency profile remain open. The 5 s React target applies from Alert creation, independently of Telemetry ACK latency.
