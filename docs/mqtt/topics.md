# MQTT contract MVP — BUILD1-04

BUILD 2: giữ nguyên contract telemetry/heartbeat/telemetry_ack bên dưới. Đã đối chiếu firmware `origin/main` 0c9cd10 và thêm subscription `events` cho structured log; B2-10 bổ sung luồng command/command_ack/state tùy cấu hình. Ma trận bàn giao và câu hỏi leader: [V3 integration](../api/build2-v3-integration.md).

Bổ sung ngày 23/10/2026: file này trước đó rỗng. Đây là lựa chọn triển khai MVP dựa trên `docs/design/iot/iot-offline-recovery.md`; cần P1/P4 review trước khi tích hợp firmware thực.

Topic prefix: `swiftletcare/{houseId}/{zoneId}/{deviceCode}`. House/Zone là PK số; deviceCode là `devices.device_id`, không phải PK số. Mã không chứa `/`, `+`, `#`. Backend đối chiếu cả topic và device/Zone/House đang active.

| Topic suffix | Hướng | QoS publish | Retain |
|---|---|---|---|
| `telemetry` | ESP32 → Backend | 0 | false |
| `heartbeat` | ESP32 → Backend | 0 | false |
| `telemetry_ack` | Backend → ESP32 | 1 | false |
| `events` | ESP32 → Backend | firmware hiện 0; backend subscribe 1 | false |

Backend subscribe `swiftletcare/+/+/+/telemetry`, `swiftletcare/+/+/+/heartbeat` và BUILD2 thêm `swiftletcare/+/+/+/events`; tự reconnect và subscribe lại, kể cả broker chưa sẵn sàng lúc ứng dụng khởi động. Mỗi instance phải có MQTT_CLIENT_ID khác nhau; MVP chỉ chạy một backend instance.

Telemetry JSON (UTF-8, tối đa 16 KiB):

```json
{"device_id":"ESP32-LOCAL","event_id":"sample-001","timestamp":"2026-10-23T00:00:00Z","temperature":29.5,"humidity":80,"co2":null,"light":12,"sensor_status":"PARTIAL_ERROR"}
```

Bắt buộc device_id (1..100 ký tự), event_id (1..150), timestamp có timezone, sensor_status. Bốn trường đo nullable; OK khi cả bốn có số, ERROR khi cả bốn null, PARTIAL_ERROR khi có 1..3 số. Theo [NFR-IOT-004](../requirements/NFR.md), số phải hữu hạn và nằm trong dải **temperature -10..60°C, humidity 0..100%, co2 0..5000 ppm, light 0..100000 lux**, bao gồm cả hai biên. Ngoài dải bị từ chối trước khi lưu, không có ACK STORED; cảm biến lỗi gửi null với status tương ứng, không dùng số 0 giả. Timestamp không quá 5 phút trong tương lai; bản đo cũ được phép replay nhưng vẫn phải qua validation. Trường lạ (kể cả schemaVersion) bị từ chối trong MVP. PostgreSQL/DTO chuẩn hóa timestamp về microsecond, bỏ phần nhỏ hơn microsecond trước khi lưu/so sánh.

Heartbeat:

```json
{"device_id":"ESP32-LOCAL","timestamp":"2026-10-23T00:00:00Z"}
```

Backend dùng thời điểm **nhận** trên DB, không dùng timestamp nguồn để tính Online. Heartbeat nguồn cũ vẫn có thể xác nhận kết nối đang hoạt động. Chỉ thiết bị ESP32 được gửi Telemetry/Heartbeat trong MVP; CAMERA là nguồn CountLog riêng.

Application ACK:

```json
{"device_id":"ESP32-LOCAL","event_id":"sample-001","status":"STORED"}
```

Chỉ phát sau commit. Retry cùng `(device_id,event_id)` và cùng nội dung nhận lại STORED, không phát thêm telemetry.updated. Khác nội dung, sai payload/device/topic hoặc DB/commit lỗi: không phát STORED. ACK bị mất → producer retry cùng bản tin. Không có outbox; ACK/realtime không được bảo đảm giao nếu process/broker chết sau commit. Frontend hồi phục qua REST, ESP32 hồi phục qua retry. Retained replay bị từ chối; producer phải giữ retain=false (MQTT 3 không luôn chuyển cờ retain của publish trực tiếp tới subscriber đang kết nối).

Heartbeat mặc định mỗi 2 giây, timeout 6000 ms, scheduler mỗi 1000 ms; cấu hình HEARTBEAT_TIMEOUT_MS và STATUS_SCAN_MS. Chỉ phát status event khi Online thay đổi. Chưa subscribe availability/LWT trong MVP.

Broker Compose chỉ dành cho local: anonymous, bind host 127.0.0.1. Trước triển khai ngoài local cần MQTT authentication/ACL theo device/topic và TLS; topic khớp payload không thay thế xác thực publisher.

## Device events — BUILD 3

Ví dụ payload lỗi cảm biến:

```json
{"device_id":"ESP32-LOCAL","event_type":"SENSOR_FAULT","sensor":"DHT22","reason":"INVALID_OR_NAN_READING","timestamp":"2026-11-02T14:00:00Z"}
```

Backend nhận `SENSOR_FAULT`, `SENSOR_RECOVERED` và `MANUAL_LIMIT`. `sensor` bắt buộc 1–80 ký tự, `reason` 1–200 và `device_id` 1–100. Timestamp UTC hoặc có offset, tối đa 5 phút trong tương lai. Device/topic/House/Zone phải khớp, ESP32 enabled, House và Zone active. Retained input, trường lạ và loại lạ bị từ chối.

Firmware đưa `SENSOR_RECOVERED` vào cùng hàng đợi sự kiện sau 3 lần đọc DHT22 hợp lệ liên tiếp, với `sensor=DHT22` và `reason=THREE_GOOD_READINGS`. Khi nhận sự kiện, Alert Engine bỏ điều kiện lỗi của cảm biến tương ứng. Cảnh báo Zone được ghi `cleared_at` khi không còn điều kiện lỗi hoạt động. Trạng thái workflow vẫn do API xử lý cảnh báo quản lý. `MANUAL_LIMIT` là thông tin chẩn đoán. Cảnh báo manual dựa vào mode và telemetry kéo dài theo luật của Alert Engine.

Backend ghi structured log và lưu trạng thái qua Alert Engine. Kênh này không có application ACK hoặc bảng log raw `device_events`. Firmware chờ đồng bộ thời gian trước khi gửi từ hàng đợi. Broker triển khai cần ACL theo đúng topic thiết bị và subscription của Backend.


### B2-10 — Điều khiển bốn actuator

Bật bằng `CONTROL_COMMANDS_ENABLED=true` trên broker test được cô lập. Khi triển khai
broker dùng chung, chỉ cấp quyền publish `command` cho Backend và chỉ cho ESP32 publish
`command_ack`/`state` trên topic của chính thiết bị đó. Backend chờ broker xác nhận
subscription trước khi nhận lệnh qua REST.

| Suffix | Hướng | QoS publish | Retain |
|---|---|---:|---|
| `command` | Backend → ESP32 | 1 | false |
| `command_ack` | ESP32 → Backend | 0 | false |
| `state` | ESP32 → Backend | 0 | false |

`command_id` là UUID. `expiresAt` dùng UTC nguyên giây. `SET_MODE` có riêng
`targetMode` (AUTO/MANUAL); `SET_STATE` có riêng `targetState` (ON/OFF trên MQTT,
boolean trên REST). `actuator` là FAN, MIST, HEATER hoặc AMPLIFIER. Mode áp dụng
chung cho ESP32. Bản tin `state.actuators` chứa trạng thái của từng đầu ra; các
trường `actuator/state/gpio_state` ngoài cùng là bản chiếu FAN cho firmware cũ.

REST 202 là đã lưu PENDING. Backend chỉ kết thúc ACKNOWLEDGED khi nhận ACK APPLIED
phù hợp (hoặc DUPLICATE với originalStatus=APPLIED); trạng thái đầu ra lấy từ
`state`. ACK sai thiết bị, sai loại actuator hoặc đến sau hạn không đổi kết quả.
ACK REJECTED giữ reason của firmware; quá hạn chờ ACK thành FAILED/ACK_TIMEOUT.
Lệnh còn PENDING khi quá hạn thành EXPIRED. Không tự gửi lại lệnh ở tầng ứng dụng.
