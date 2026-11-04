# BUILD 2 — baseline V3 của nhóm và đề xuất còn lại

Ngày đối chiếu: 02/11/2026. Nhánh `htran`.

Cập nhật implementation: đã có ZoneConfig GET/PUT trên bảng V3, kiểm version/validation và trả syncStatus UNKNOWN. Phần sync/per-device dưới đây vẫn là đề xuất; chưa publish cfg/nhận cfg_ack hoặc tạo V4. House và control read APIs mới xem [handoff](../../api/build2-handoff.md).

## Baseline đã kiểm tra

Sau fetch origin ngày 02/11/2026, đã tích hợp nguyên bản `V3__add_missing_erd_tables.sql` từ `origin/htran`, commit `080fbba50bc4b28c230921f3013ced6e79a75694`. V1/V2 không đổi. V3 tạo thêm 10 bảng: zone_configs, sensors, actuators, control_commands, anomaly_events, alerts, alert_histories, notification_deliveries, reports, harvest_logs; tổng 17 bảng. V3 không có INSERT/UPDATE/DELETE hay ALTER bảng V2. Chỉ chạy trên DB test mới `build2_v3_review_20261102`; không chạy trên DB hiện tại của người dùng. Xem [báo cáo tích hợp](../../api/build2-v3-integration.md).

`db/reference/01_schema.sql` là SQL reference 17 bảng trong search_path mặc định, không phải migration V1 hiện tại dù comment đầu file cũ nhắc đến Flyway. Không import file này. Reference seed yêu cầu database rỗng 17 bảng, có ID cố định, không được áp dụng lên V2. Không sửa V1/V2, seed ID hoặc Flyway history.

V3 đã do nhóm sở hữu, không tạo migration trùng. Số tiếp theo trong checkout là V4 nhưng **đang tạm dừng tạo/áp dụng migration mới theo yêu cầu người dùng**. device_config_sync/device_events chỉ là đề xuất dưới đây, không có file Flyway mới. Trước khi mở lại phải đối chiếu remote/history và leader duyệt.

## Dùng bảng V3 và phần còn cần duyệt cho B2-10/B2-11

Bảng V3 thuộc `swiftletcare`, BIGINT IDENTITY, timestamp TIMESTAMPTZ, FK ON DELETE RESTRICT. Bảng actuator/command/config đã có, không đề xuất tạo lại. V3 của nhóm cũng có các bảng alert/anomaly/report; việc tiếp nhận schema không triển khai các module ngoài phạm vi.

| Bảng | Nội dung đề nghị | Ràng buộc/index |
|---|---|---|
| actuators (đã có V3) | device_id, kind, mode, `on`, manual_until; dùng nguyên bản | FK devices; kind MIST/FAN/HEATER/AMPLIFIER, mode AUTO/MANUAL. V3 chưa có unique(device_id,kind), không tự bổ sung |
| control_commands (đã có V3) | actuator_id, requested_by NOT NULL, command_id, action, target_state nullable, target_mode nullable, requested_at, status, sent_at, ack_at, failure_reason, expires_at | command_id UNIQUE; XOR target theo action; expiry > requested_at; giữ PENDING/SENT/ACKNOWLEDGED/FAILED/REJECTED/EXPIRED. Dùng index status/expiry và actuator/requested_at của nhóm |
| zone_configs (đã có V3) | Dùng nguyên bản V3: zone_id UNIQUE, version, updated_at, ngưỡng và interval | V3 kiểm humidity/on<off, temp min<max, hysteresis>0, CO2/light min<=max, intervals 1..60. Chưa có upper range/finite constraints, heartbeat cho phép 1..60. Service cần validate NFR và heartbeat=2; không sửa V3 để siết constraint |
| device_config_sync | device_id PK/FK, desired_version, applied_version nullable, last_ack_version nullable, last_ack_status nullable, published_at nullable, ack_at nullable, failure_reason nullable | desired_version >=1; applied_version >=1 nếu có; row riêng mỗi device. desired_version không bị giảm bởi ACK cũ; không dùng bảng này làm lịch sử mọi ACK |

Sync đề nghị dùng version mục tiêu và version áp dụng riêng. Khi PUT thay đổi: khóa Zone/config, so sánh version client; thay đổi hợp lệ tăng đúng 1, không thay đổi giữ version; cập nhật desired_version các ESP32 enabled trong transaction. Sau commit mới publish retained config; publish lỗi lưu trạng thái chờ retry cùng version. ACK cũ không đổi desired/current version; ACK xác nhận đúng desired version mới đánh dấu applied. STALE ghi nhận chẩn đoán, không tăng revision tự động. Cần chốt payload ACK và firmware báo applied version trước khi viết migration/handler.

Thêm thiết bị khi Zone đã có config phải tạo sync row và gửi lại cùng current version. Broker reconnect republish current config, không tăng version. Cần chốt outbox hoặc worker retry bền vững; retained của broker không tự chứng minh mọi device đã áp dụng.

Command đề nghị persist PENDING trước publish, MQTT retain=false. ACK đúng device/command dưới khóa row, conditional transition tránh ACK/publish callback/timeout ghi đè lẫn nhau. ACK sớm trước SENT vẫn có thể áp dụng nếu command đã commit và còn hợp lệ; publish callback không ghi đè terminal state. Duplicate ACK không cập nhật actuator hay phát thêm event. ACK muộn phải theo quyết định terminal/timeout đã duyệt. WebSocket phát sau commit; gpio_state chỉ là report GPIO, không chứng minh relay/tải thực hoạt động.

**Điểm cần chốt:** `api-conventions.md` gợi ý FAILED + failure_reason=ACK_TIMEOUT; enum reference có EXPIRED; `database-open-decisions.md` còn OPEN. Không thêm enum ACK_TIMEOUT hay tự quyết định ACK muộn có được đổi FAILED/EXPIRED. 10s ACK/15s expiry xuất hiện trong offline design nhưng cần thống nhất mapping và mốc tính deadline.

## Đề xuất riêng B2-12, chưa được duyệt

Không dùng `anomaly_events`. Nếu cần persist, đề nghị `device_events`:

| Field | Đề nghị |
|---|---|
| id | BIGINT IDENTITY PK |
| device_id | BIGINT NOT NULL FK devices RESTRICT |
| event_id | VARCHAR(150) NOT NULL, producer giữ ổn định khi retry — cần xác nhận firmware có cấp |
| type | SENSOR_FAULT hoặc MANUAL_LIMIT |
| occurred_at / received_at | TIMESTAMPTZ NOT NULL |
| payload | JSONB structured details đã validate, giới hạn kích thước |

Đề nghị UNIQUE(device_id,event_id), index(device_id,occurred_at DESC,id DESC). Cùng key khác payload từ chối; timestamp/nguồn/topic/type phải hợp lệ. Chưa chốt retention, chi tiết payload từng type, nguồn ID hay topic suffix. Nếu firmware không có stable event_id, cần quyết định dedup khác; không tự coi timestamp là unique key.

Đã đối chiếu `origin/main` commit `0c9cd10`, `firmware/src/sketch.ino`: topic events, payload device_id/event_type/sensor/reason/timestamp, chưa có event_id. Backend đã subscribe events và validate/log structured cho SENSOR_FAULT/MANUAL_LIMIT, không persist/alert. MANUAL_LIMIT mới được thử bằng MQTT producer mô phỏng theo envelope này, firmware chưa có producer type đó; cần leader xác nhận schema MANUAL_LIMIT. SENSOR_RECOVERED của firmware hiện ngoài hai type được yêu cầu, backend từ chối. Timestamp rỗng khi firmware chưa có NTP cũng bị từ chối. Không tự coi timestamp là idempotency key.
