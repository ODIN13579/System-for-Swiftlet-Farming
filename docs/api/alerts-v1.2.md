# Alert, AI và MQTT v1.2

| Giao thức | Hợp đồng theo implementation hiện tại |
| --- | --- |
| Alert REST | `GET /api/v1/alerts?houseId=...` với zone/status/level và phân trang tùy chọn. `GET /api/v1/alerts/{id}`, `/history`, `PATCH /api/v1/alerts/{id}/status`. READ theo House, thay đổi trạng thái cần WRITE. Workflow NEW → IN_PROGRESS → RESOLVED. `clearedAt` là thời điểm điều kiện phục hồi, độc lập với workflow. |
| Realtime | `/ws/houses/{houseId}`, `{"type":"alert.created","data":{...}}` và `alert.updated` sau commit. `data` dùng camelCase. Trường chung gồm `id`, `houseId`, `zoneId`, `deviceId`, `type`, `level`, `status`, `message`, `createdAt`, `handledAt` và `clearedAt`. Engine không thêm `anomalyEventId` hoặc `snapshotId`. Publisher Predator và DTO REST có hai trường này. Auth qua Bearer hoặc cookie `SC_WS`, kiểm tra quyền House lúc kết nối và trước delivery. |
| Predator | `POST /api/ai/snapshots` multipart `camera_id` và `file` với `X-Service-Key`, tối đa 300 KiB. `POST /api/ai/events` dùng cùng key, tham chiếu snapshot thuộc camera, lặp cùng `event_id` và nội dung trả kết quả cũ. `POST /api/ai/heartbeat` dùng service key. |
| Ảnh riêng tư | `GET /api/snapshots/{id}` qua tài khoản đã xác thực và quyền READ đúng House. `Cache-Control: private, no-store`. Giữ đề xuất retention 7 ngày ở trạng thái chờ phê duyệt cleanup. |
| MQTT | `swiftletcare/{house}/{zone}/{device}/telemetry`, `/heartbeat`, `/events`. Telemetry có `event_id`, `device_id`, `timestamp`, phép đo nullable và `sensor_status`. Backend publish `/telemetry_ack` với `device_id`, `event_id`, `status=STORED` sau lưu. Firmware giữ phần tử đến khi xử lý đúng ACK. |
| Thống kê | [API B3-20](reports-build3.md) dùng House READ, bucket và CSV UTC+07. |

## Quyết định và điểm mở

| Mục | Jira/hiện trạng | Trạng thái và người xác nhận |
| --- | --- | --- |
| ENV_THRESHOLD | Ba mẫu liên tiếp vi phạm ngưỡng đã cấu hình. Tách thời gian phát hiện khỏi backend → UI. | Đã thể hiện trong engine; P5 cần xác nhận đường hiển thị live. |
| ESP32 offline | Jira Alert >9 giây. Build 2 có trạng thái device offline với ngưỡng riêng 6 giây mặc định của Ingestion. | Hai tín hiệu cần được ghi riêng khi đo; P1/P4 xác nhận nhãn UI. |
| Camera offline | Jira >30 giây với nhịp 10 giây. | P2/P4 xác nhận cấu hình triển khai hiện trường. |
| P17 / P41 | Manual 5 phút, dedup Alert 5 phút theo Jira và mặc định backend. | P1/P4 kiểm tra override khi triển khai. |
| P35 / P36 | Daily Entry Audit kiểm tra độ phủ. Chưa chốt ngưỡng/ngày baseline và xử lý đa camera cho FLOCK_DROP. | OPEN, P4 và nhóm nghiệp vụ. |
| Predator | Jira yêu cầu 3 frame và cooldown 60 giây, ảnh tối đa 300 KB trong mô tả; backend cap chính xác 300 KiB và AI quyết định trước khi gửi. | P2/P4 xác nhận đơn vị, boundary/cooldown và retention thực tế. |
| React | Alert Center uses REST and House WebSocket. Reports uses authenticated aggregate/CSV endpoints. | Real Edge PREDATOR evidence recorded in the completion audit. Wokwi scenario acceptance remains open. |
| Schema WS và REST | Engine bỏ hai trường ảnh, còn REST trả nullable. Frontend dùng cùng DTO cho mọi bản tin sẽ cần xử lý field vắng. | OPEN, P4/P5 xác nhận chấp nhận trường tùy chọn hay chuẩn hóa payload trước khi UI chuyển sang dữ liệu live. |

Nội dung này đối chiếu v1.2 với [Build 2](build2-handoff.md), [MQTT](../mqtt/topics.md), [P4 handoff](../testing/build3-backend-handoff.md) và [sequence](../sequence/build3-alerts.md).
