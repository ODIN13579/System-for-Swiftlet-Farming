# BUILD3-04 — P4 Backend Final Review & Handoff

**Ngày rà soát** 25/11/2026 · **Nhánh** `be-b3` · **HEAD** `d78231b` cùng các thay đổi local đã rà soát.

**Kết quả Backend** `BUILD SUCCESS`, **76/76 test pass**, 0 failure, 0 error, 0 skip với `DB_TEST_ENABLED=true`. B3-11 chạy riêng **6/6 pass**. Diễn tập V9 → V11 thành công trên PostgreSQL kiểm thử. BUILD3-04/P4 còn các điều kiện đóng ở cuối tài liệu.

## Trạng thái P4

| Hạng mục | Trạng thái | Bằng chứng và phạm vi |
|---|---|---|
| B3-10 — Alert Engine | PASS | 10/10. Xác minh ngưỡng môi trường, cảm biến lỗi, ESP32 offline, manual condition, chống trùng và phục hồi. [Test](../../backend/src/test/java/com/swiftletcare/AlertEngineIntegrationTest.java) · [Engine](../../backend/src/main/java/com/swiftletcare/alert/AlertEngine.java) |
| B3-11 — Alert REST, Workflow, House Realtime | PASS | 6/6 trên API hiện tại. Bộ lọc, workflow, audit và phạm vi House có bằng chứng riêng bên dưới. Đồng bộ hợp đồng API là điều kiện bàn giao riêng. [Test](../../backend/src/test/java/com/swiftletcare/AlertWorkflowIntegrationTest.java) |
| B3-12 — AI Predator & Snapshot | PASS | 4/4. Service key, upload/đọc ảnh riêng tư, idempotency, liên kết Event/Alert và dedup. [Test](../../backend/src/test/java/com/swiftletcare/AiPredatorIntegrationTest.java) |
| B3-13 — Account Management | PASS | 5/5. Global ADMIN quản lý tài khoản, profile, đổi mật khẩu, logout, kiểm tra `token_version` và từ chối JWT bị thu hồi. [Test](../../backend/src/test/java/com/swiftletcare/AccountManagementIntegrationTest.java) |
| B3-14 — Camera Heartbeat | PASS | 5/5. Trong lượt verify này, Alert được tạo sau **30,336 giây** và đến House WebSocket sau **30,343 giây** tính từ heartbeat cuối. Cùng Zone, mỗi camera có vòng đời offline riêng. [Test](../../backend/src/test/java/com/swiftletcare/CameraHeartbeatIntegrationTest.java) |
| B3-15 — Daily Entry Audit & FLOCK_DROP | PARTIAL | Audit chỉ đọc pass 5/5. **BLOCKED** đối với scheduler, tạo Alert và nghiệm thu chống trùng thực tế. Chờ P35/P36, baseline và ngưỡng FLOCK_DROP. [Test](../../backend/src/test/java/com/swiftletcare/DailyEntryAuditIntegrationTest.java) · [Handoff BUILD2](../api/build2-handoff.md#L54) |
| B3-16 — Immediate House Role Reassignment | PASS | 7/7. Owner/Global ADMIN, JWT cũ thấy quyền mới, bảo vệ OWNER cuối trong PATCH membership, hai tình huống cập nhật đồng thời và WebSocket đọc quyền hiện tại. [Test](../../backend/src/test/java/com/swiftletcare/HouseRoleReassignmentIntegrationTest.java) |
| B3-17 — Snapshot Cleanup & Query Tuning | PARTIAL | Dry-run, tuning và 6 ca riêng pass. **BLOCKED** đối với xóa thực sự. Chờ duyệt retention và phạm vi xử lý dữ liệu. Benchmark duy trì nguồn đo ở dưới. [Cleanup test](../../backend/src/test/java/com/swiftletcare/SnapshotCleanupIntegrationTest.java) · [V11](../../backend/src/main/resources/db/migration/V11__count_logs_history_index.sql) |

PASS trong bảng là kết quả trên Backend và dữ liệu kiểm thử. Việc triển khai lên môi trường gốc và chấp thuận Contract v1.2 chưa được xác nhận.

## Nghiệm thu riêng B3-11

| Tiêu chí | Kết quả kiểm thử |
|---|---|
| List, detail, filter | `GET /api/v1/alerts` lọc House, status, level và Zone. Test có nhiều Zone, Zone inactive và House khác, kiểm tra tổng số bản ghi, phân trang và thứ tự ổn định khi `createdAt` bằng nhau. |
| Workflow | `NEW → IN_PROGRESS → RESOLVED` hợp lệ. Bỏ bước và đổi ngược `RESOLVED → NEW` trả 409. Lặp cùng trạng thái không tạo thêm audit. |
| Audit | Cả hai bản ghi lịch sử trả đúng actor, cặp trạng thái và note. `changedAt` theo thời gian DB và có thứ tự. `handledAt` null khi IN_PROGRESS và khớp thời điểm audit của lần RESOLVED. |
| Phân quyền REST | VIEWER cùng House đọc list, detail và history, nhưng PATCH trả 403. User thuộc House khác nhận 403 trên list, detail, history và workflow của House đích. |
| Scope và quyền WebSocket | PATCH thật phát `alert.updated` cho VIEWER trong House đích. Socket đang mở hợp lệ của House khác không nhận event và vẫn nhận probe của House mình. Kết nối sai House và session mất quyền đọc bị đóng 1008. |

Review đã bổ sung kiểm tra nội dung và thời gian audit, quyền đọc history và ca hai socket ở hai House. Code cập nhật Alert và AlertHistory trong cùng transaction rồi publish sau commit. [AlertController](../../backend/src/main/java/com/swiftletcare/alert/AlertController.java) · [HouseAlertRealtime](../../backend/src/main/java/com/swiftletcare/realtime/HouseAlertRealtime.java) · [B3-11 Surefire](D:/swiftletcare-p4-review-cfb47e1b9508/maven-build/backend/target/surefire-reports/com.swiftletcare.AlertWorkflowIntegrationTest.txt) · [Log B3-11](D:/swiftletcare-p4-review-cfb47e1b9508/b3-11.log)

## Đối chiếu API và OpenAPI

Repository có [OpenAPI `0.9.0-review`](../api/openapi.yaml), chưa tìm thấy Contract v1.2 đã phê duyệt trong repo hoặc lịch sử Git đã rà soát. Kiểm tra cấu trúc YAML của repo pass với 47 operation trên 36 path. Đối chiếu tĩnh theo HTTP method và path, bỏ qua khác biệt tên path parameter, tìm được 28 operation trùng, 10 chỉ có ở các REST controller và 19 chỉ có ở draft. Số này không tính Actuator hoặc WebSocket. Các operation trùng path vẫn cần đối chiếu payload và hành vi.

| Nhóm | Chênh lệch quan sát được với draft |
|---|---|
| B3-11 Alerts | Draft list thiếu query `level`, khai `houseId`/`zoneId` là int32 trong khi Backend nhận Java `long`. `AlertResponse` của draft chưa có `houseId`, `deviceId`, `snapshotId`, `clearedAt` và chưa khai nullable cho `anomalyEventId`. Draft history có `page`/`size` và envelope `{items,page}`, mỗi item có `alertId` và `changeAt`. Backend trả mảng không phân trang, mỗi item có `changedAt` và không lặp `alertId`. Cần chốt dạng response trước khi FE dùng DTO sinh từ OpenAPI. [Draft](../api/openapi.yaml#L1425) · [Controller](../../backend/src/main/java/com/swiftletcare/alert/AlertController.java) |
| B3-11/B3-14 realtime | Notes realtime cũ mô tả envelope `{type,data}` và nhắc `alertId`. House WebSocket thực tế ở `/ws/houses/{houseId}`, dùng `data.id` cho ID alert và phát `camera.status.changed` ở B3-14. Cần đóng gói contract sự kiện cho FE, gồm quyền đọc và quy tắc đóng 1008. [Notes](../api/realtime-contract.md) · [Cấu hình WS](../../backend/src/main/java/com/swiftletcare/realtime/WebSocketConfig.java) |
| B3-12/B3-14 AI | Backend có `POST /api/ai/snapshots` dạng multipart, `POST /api/ai/events` và `POST /api/ai/heartbeat` với `X-Service-Key`, cùng `GET /api/snapshots/{id}` dùng JWT và quyền House. Draft dùng `POST /api/v1/internal/ai/anomalies` và có các route đọc anomalies chưa có controller. `POST /api/v1/internal/ai/count-logs` là route dùng chung đã có. [Controller AI](../../backend/src/main/java/com/swiftletcare/ai/AiPredatorController.java) |
| B3-13 tài khoản | Draft ghi `GET/PATCH /api/v1/users/me`, nhưng Backend dùng `GET/PATCH /api/v1/me`; các trường hồ sơ cho phép sửa là `fullName`, `phone`. Backend có `POST /api/v1/me/change-password` và các route Global ADMIN `GET/POST /api/v1/admin/users`, `PATCH /api/v1/admin/users/{id}/active`. Logout cùng path nhưng dùng JWT và không cần body, tăng `token_version` và hết hạn cookie WS. `POST /api/v1/auth/refresh` trong draft chưa có controller. [AccountController](../../backend/src/main/java/com/swiftletcare/security/AccountController.java) |
| B3-16 membership | PATCH `/api/v1/houses/{houseId}/members/{membershipId}` đã đồng bộ với draft hiện tại cho body chỉ có `role`. Các operation GET/POST members còn ở mức thiết kế. Cần quyết định thêm nếu muốn bảo vệ OWNER cuối đồng thời tại endpoint vô hiệu hóa **tài khoản** Global ADMIN; guard hiện tại áp dụng khi đổi role membership. [Contract PATCH](../api/openapi.yaml#L447) · [Membership controller](../../backend/src/main/java/com/swiftletcare/management/HouseMembershipController.java) |
| Endpoint dự thảo khác | GET command history theo actuator, notifications, harvest, reports và dashboard summary vẫn là operation draft chưa có controller tương ứng. [Danh mục API](../api/api-endpoint-catalog.md) |

## Flyway V1–V11 và triển khai từ V9

**Diễn tập đã chạy.** PostgreSQL 18.6 riêng trên ổ D, schema V9 được tạo bằng Flyway target 9 rồi nạp fixture tổng hợp. Khi chạy B3-11 bằng cấu hình mặc định, Flyway nhận schema ở V9 và áp dụng V10, V11. Checksum V1–V9 khớp trước/sau, cả 11 migration báo success. Ba index mới ở trạng thái valid/ready, `camera_source_ok` có kiểu boolean nullable. SHA-256 của fixture trước/sau giống nhau, gồm account và `token_version`, membership, telemetry, CountLog, dữ liệu ảnh, liên kết Event → Snapshot, Alert → Event/Snapshot và lịch sử Alert. Lượt verify tiếp theo xác nhận Flyway migrate lại không thực thi migration nào. [Flyway V9](D:/swiftletcare-p4-review-cfb47e1b9508/flyway-v9.txt) · [Flyway V11](D:/swiftletcare-p4-review-cfb47e1b9508/flyway-v11.txt) · [SHA-256 fixture](D:/swiftletcare-p4-review-cfb47e1b9508/fixture-preservation.txt) · [V10](../../backend/src/main/resources/db/migration/V10__camera_heartbeat.sql) · [V11](../../backend/src/main/resources/db/migration/V11__count_logs_history_index.sql)

Môi trường gốc được bàn giao ở V9. Docker engine của môi trường cũ không truy cập được trong lượt review này, nên version, tải và dung lượng hiện tại của database gốc cần được người triển khai kiểm tra lại. Các lệnh thử migration chỉ trỏ tới database diễn tập.

### Các bước triển khai cần phê duyệt

1. Chốt artifact chứa đúng migration V1–V11 và cấu hình trỏ đúng môi trường. Kiểm tra Flyway history, checksum, không có migration fail; xác nhận V10 và V11 là hai migration pending. Kiểm tra tài nguyên trống cho index, WAL và backup theo kích thước bảng thực tế.
2. Tạo backup nhất quán và xác minh quy trình restore trên môi trường riêng. Kiểm tra dữ liệu trùng có thể chặn unique index V10 bằng truy vấn chỉ đọc bên dưới. Nếu có kết quả hoặc checksum khác, dừng rollout để duyệt cách xử lý.
3. Chọn cửa sổ bảo trì cho `ALTER TABLE` và `CREATE INDEX` thông thường trong V10/V11. Giảm ghi và kiểm soát các transaction giữ khóa lâu. Dừng instance cũ trước khi mở luồng heartbeat camera, vì scanner ESP32 cũ có thể đánh dấu camera online thành offline sớm. Chỉ cho một instance mới chạy Flyway trong bước upgrade và chờ readiness.
4. Kiểm tra migration history success, cả ba index valid, readiness và smoke test đọc Telemetry/CountLog, Alert/audit, snapshot được bảo vệ và camera heartbeat. Kiểm tra first heartbeat và timeout trên camera thử được cho phép. Sau đó mở lưu lượng và giám sát lock, lỗi 5xx và độ trễ query. Giữ snapshot cleanup ở mặc định tắt lịch.
5. Nếu migration hoặc smoke test lỗi, chặn mở lưu lượng và giữ log. Rollback app chỉ dùng artifact đã diễn tập tương thích V11, gồm migration files khớp schema để Flyway validate. Phương án restore backup hoặc xử lý dữ liệu cần phê duyệt riêng, có tính đến các ghi mới sau thời điểm backup.

```sql
select device_id, count(*)
from swiftletcare.alerts
where type = 'CAMERA_OFFLINE'
  and cleared_at is null
  and device_id is not null
group by device_id
having count(*) > 1;
```

## B3-17 — benchmark thực tế và dry-run

Lượt benchmark B3-17 dùng PostgreSQL 18.6 với 622.080 Telemetry, 311.040 Count Logs, 252.000 Alerts và 24.192 Snapshot tổng hợp; 7 lượt đo sau warm-up, trang đầu 20 dòng, shared read bằng 0 trong các lượt báo cáo. Truy vấn 7 ngày dùng khoảng UTC `[2026-10-08, 2026-10-15)`. Số đo là median `EXPLAIN (ANALYZE, BUFFERS)`, chưa đo production, cache lạnh hoặc trang sâu.

| Truy vấn | Dòng khớp | ms trước → sau | Shared hit trước → sau | Plan chính |
|---|---:|---:|---:|---|
| Telemetry 7 ngày, trang | 2.016 | 0,141 → 0,138 | 26 → 26 | Index Scan |
| Telemetry 7 ngày, đếm | 2.016 | 0,465 → 0,503 | 28 → 28 | Index Only Scan |
| Count Logs 7 ngày, trang | 168 | 0,729 → 0,198 | 158 → 22 | Bitmap + Sort → Index Scan V11 |
| Count Logs 7 ngày, đếm | 168 | 0,301 → 0,190 | 19 → 7 | Index Only Scan, dùng V11 |
| Alerts toàn House, trang API | 7.000 | 54,413 → 0,322 | 3.909 → 58 | Seq Scan + Hash Join → Index Scan theo Zone |
| Alerts toàn House, đếm | 7.000 | 1,649 → 1,711 | 44 → 44 | Index Only Scan trên Alerts |
| Alerts 7 ngày, trang SQL | 560 | 1,627 → 0,310 | 575 → 58 | Bitmap Scan → Index Scan theo Zone |
| Alerts 7 ngày, đếm SQL | 560 | 0,315 → 0,308 | 10 → 10 | Index Only Scan trên Alerts |
| Metadata Snapshot 7 ngày | 84 | 0,188 → 0,152 | 25 → 25 | Index Scan |
| Tải Snapshot theo ID và camera | 1 | 0,138 → 0,140 | 6 → 6 | Index Scan theo PK |

Alerts 7 ngày và metadata Snapshot là truy vấn đo SQL. API Alerts hiện hành chưa có bộ lọc thời gian 7 ngày và Snapshot chưa có endpoint liệt kê. Các phép đo hỗ trợ index Count Logs và thay đổi trang Alerts, không dùng để kết luận Telemetry/Snapshot tăng tốc. [Plan trước](D:/swiftletcare-b317-d5ad6873bb/before-plans.json) · [Plan sau](D:/swiftletcare-b317-d5ad6873bb/after-plans.json) · [Fixture SQL](D:/swiftletcare-b317-d5ad6873bb/seed.sql)

Dry-run B3-17 trên fixture ghi 6 ảnh quá cutoff, 4 còn liên kết, 2 đủ điều kiện xem xét và 0 ảnh xử lý/xóa. Lỗi DB giả lập ghi 1 lỗi và lượt thử lại thành công. Snapshot hiện lưu `BYTEA` trong PostgreSQL. Lịch tắt mặc định, nếu đặt lịch và retention thì vẫn chỉ đọc. Mốc 7 ngày là đề xuất chưa được duyệt. Cần chốt retention, chính sách giữ bằng chứng còn liên kết và quyền cho phép xóa thực sự. [Chính sách đang mở](../ai/ai-architecture-overview.md#L21) · [Code cleanup](../../backend/src/main/java/com/swiftletcare/ai/SnapshotCleanup.java) · [Log dry-run](D:/swiftletcare-b317-d5ad6873bb/test.log)

## Điều kiện trước khi đóng BUILD3-04/P4

- Chốt P35/P36 và baseline FLOCK_DROP, gồm số ngày và độ phủ cần có, xử lý dữ liệu thiếu hoặc chồng lấn, chọn HOURLY/DAILY và cách gộp camera. Chốt ngưỡng trước khi nghiệm thu scheduler, chống trùng và phát sự kiện đúng House.
- Chốt chính sách retention và phê duyệt việc xóa snapshot thật. Hiện chỉ có dry-run.
- Đồng bộ API/WS với FE và AI theo hợp đồng có phê duyệt, đặc biệt Alert History, Profile/Logout, AI Event/Heartbeat/Snapshot. Chốt cách xử lý nếu vô hiệu hóa tài khoản là OWNER hiệu lực cuối cùng của House.
- Kiểm tra môi trường gốc V9, phê duyệt cửa sổ triển khai, backup/restore và smoke sau V11. Bản Docker triển khai và môi trường gốc chưa được nghiệm thu trong lượt này.
- Chốt artifact được duyệt từ working tree hiện tại và đính kèm log/plan cho nhóm. Các đường dẫn ổ D trong tài liệu trỏ tới bằng chứng trên máy kiểm thử.

## Lệnh và bằng chứng kiểm thử cuối

```powershell
$env:DB_TEST_ENABLED = 'true'
mvn -f backend/pom.xml '-Dtest=AlertWorkflowIntegrationTest' test
mvn -f backend/pom.xml verify
```

Các lệnh chạy từ bản sao nguồn trên ổ D với PostgreSQL và MQTT kiểm thử riêng, để giữ nguyên artifact `backend/target` đang được Git theo dõi. Lượt B3-11 có **6/6** và lượt verify có **76/76** pass. [Log B3-11](D:/swiftletcare-p4-review-cfb47e1b9508/b3-11.log) · [Log verify](D:/swiftletcare-p4-review-cfb47e1b9508/verify.log)

Review kiểm tra Git status/diff trước và sau, giữ các thay đổi local trước đó và phần mã sản xuất. Thay đổi trong lượt review gồm tài liệu này và các kiểm tra B3-11 được bổ sung. V1–V11 giữ nguyên. Chưa commit, push, migrate database gốc, xóa ảnh/dữ liệu hoặc chạy Flyway repair.
