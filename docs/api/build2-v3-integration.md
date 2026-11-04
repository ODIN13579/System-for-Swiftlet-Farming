# V3 của nhóm — tích hợp có chọn lọc trên htran

> Đây là lịch sử tích hợp có chọn lọc. Người dùng sau đó đã cho phép fast-forward tới origin/htran; code remote được giữ và BUILD2 được khôi phục/test lại. Trạng thái mới: [xác minh trước push](build2-push-verification.md).

Ngày 02/11/2026. Không commit/push/merge. HEAD vẫn `03fa14c52115d016fbceb83180748a78685fa78b`; các file lấy về là thay đổi working tree, **không phải đã merge origin/htran**.

## Nguồn remote và nội dung

Đã `git fetch origin`, kiểm tra toàn bộ nhánh remote đang có: origin/htran, origin/main. Chỉ origin/htran có V3.

| Nhánh / commit | Nội dung |
|---|---|
| origin/htran — `080fbba50bc4b28c230921f3013ced6e79a75694` | V3__add_missing_erd_tables.sql và seed_demo_compatible.sql |
| origin/htran — `7590aec105e49573e97213d2229f379024cae446` (tip) | swiftletcare_demo_baseline_seed.sql; xóa hai reference cũ trên remote; frontend mới; thay đổi LocalData/Realtime và README |
| origin/main — `0c9cd10` (tip) | Firmware BUILD2, chưa có V3; dùng để đối chiếu contract, không đưa firmware vào working tree |

V3 chỉ CREATE 10 bảng/index: zone_configs, sensors, actuators, control_commands, anomaly_events, alerts, alert_histories, notification_deliveries, reports, harvest_logs. V1/V2 không thay đổi giữa HEAD và origin/htran. V3 không có dữ liệu seed hoặc ALTER/UPDATE/DELETE bảng cũ. Tổng schema sau migrate là 17 bảng.

Đã đưa nguyên byte vào working tree:

- `backend/src/main/resources/db/migration/V3__add_missing_erd_tables.sql` — SHA256 `0a518c3f2ac94ed2203d75ef9c4b355d17d2da2daf79fd36217f22b4fb75b525`.
- `backend/src/main/resources/db/reference/seed_demo_compatible.sql` — SHA256 `2c53fe02bb7dadab142b5e78f133abb1d5604875b875832a9ef135974fabeb6b`.
- `backend/src/main/resources/db/reference/swiftletcare_demo_baseline_seed.sql` — SHA256 `3fcd4bcac87990370160f169ef58000737b1ac3886419c75277600e81382d234`.

Không lấy thay đổi frontend, reset password local, debug log principal của WebSocket hoặc việc xóa reference cũ. Hai reference cũ có ID cố định được giữ nguyên. Không chạy SQL reference tự động khi app khởi động. Hai seed mới dùng lookup động; ID số không được bảo đảm giống locked dataset cũ. Không đổi ID hoặc nhập seed vào DB hiện tại.

## Backup và xung đột

Backup trước tích hợp: `backend/logs/backups/20261102-210341-before-team-v3/`, gồm working-files.zip (24 file thay đổi), working-tree.patch, manifest.json (SHA256 từng file), status.txt và head.txt. Backup được Git ignore. Stash có sẵn của người dùng không bị sửa/pop.

Không có file V3 local trước tích hợp; ba đường dẫn mới không đè file đang sửa. Không có xung đột nội dung trực tiếp khi lấy chọn lọc. Không fast-forward toàn nhánh vì tip remote còn các thay đổi ngoài phạm vi và xóa reference. Các thay đổi backend/Dockerfile, backend/pom.xml, requirements/scope và .vscode có sẵn vẫn được giữ.

Xung đột logic đã điều chỉnh:

- Test migration trước đó cố định version=2; nay kiểm version=3, tổng 17 bảng và migrate lại không làm gì.
- Đề xuất schema cũ coi actuator/command/config chưa có; nay dùng bảng V3, không tạo lại chúng. Không tạo V4. device_config_sync/device_events vẫn chưa có và chưa được duyệt.
- Theo quyết định mới của người dùng, Access cấp READ/WRITE toàn hệ thống cho account có global role ADMIN đang active; House ADMIN không bypass, inactive account bị từ chối. House/Zone active và device enabled vẫn là điều kiện vận hành.
- Danh sách House hỗ trợ Global ADMIN; API security matrix/OpenAPI/handoff cập nhật tương ứng. Không tin global role do client đưa trong JWT.

## Thử nghiệm trên DB test mới

DB `build2_v3_review_20261102`, PostgreSQL test host 55433, broker test host 51884; không dùng DB `build2_test` có sẵn hoặc DB nghiệp vụ. Tạo DB mới, không DROP/reset/clean volume.

1. Flyway target=2: BackendIntegrationTest 2/2 pass, history chỉ V1/V2.
2. Chèn fixture House/Zone/ESP32/Telemetry riêng có ID 900001 vào DB test để theo dõi dữ liệu trước nâng cấp; không thay seed gốc. Đây là fixture test, không phải ID production.
3. Chạy nguyên bản V3 bằng Flyway qua application test. History V1/V2/V3 success; 17 bảng. Regression BUILD1 + BUILD2: **19 tests, failures=0, errors=0, skipped=0**, clean verify/JAR pass lúc 21:14:19 (Asia/Saigon).
4. Kiểm tra fixture V2 giữ nguyên ID/device/event, timestamps và mọi giá trị đo sau nâng V3. Thử hai seed mới tuần tự hai lần trên DB test; so sánh số row 17 bảng và hash từng row có sẵn của 7 bảng core. Kết quả chi tiết: `backend/logs/v3-review-seed-results.json`.

Kết quả seed **PASS trong điều kiện chạy tuần tự cùng giờ/ngày**: lần hai không tăng số row ở bất kỳ bảng nào; mọi row core có sẵn trước seed giữ nguyên hash. Không suy ra seed an toàn khi chạy đồng thời hoặc chạy lại ngày/giờ khác (seed compatible dùng CURRENT_TIMESTAMP/CURRENT_DATE). Seed tạo 7 alert/91 notification demo trong DB review; số này là dữ liệu mô phỏng, không phải alert production do events handler sinh ra. DB test cũ `build2_test` vẫn version 2; không bị nâng trong phiên này. Backend source/resources/POM hiện tại đối chiếu khớp snapshot pass.

Actual environment: Java 21/Maven 3.10.0, dùng `-Djava.version=21` vì POM local yêu cầu Java25. Lần thử đầu bị bytecode Java25 ghi đè trong target khi test Java21; lần chạy thành công dùng snapshot source riêng để tránh build IDE. Không xem lần lỗi là pass. Java25 baseline, backend Docker image và firmware vật lý: Not Tested.

Lệnh PowerShell chạy lại (DB review đã được tạo):

```powershell
./scripts/test-build2.ps1 -Java21Compatibility -Offline -IsolatedBuild -TestDatabase build2_v3_review_20261102
```

Log Maven: `backend/logs/build2-verify.log`. Source snapshot/JUnit/JAR lần pass: `backend/logs/isolated-build/20261102-211207-788/`. Không chạy JAR/app hiện tại trỏ DB nghiệp vụ trước khi nhóm quyết định rollout, vì Flyway sẽ thấy V3 là pending. `-IsolatedBuild` chỉ tránh xung đột output IDE, không đổi code nguồn chính. Script cũng xử lý Docker stderr progress của Windows PowerShell, kiểm exit code native thay vì coi progress là failure.

## Đối chiếu firmware và phần độc lập đã làm tiếp

Đã đọc `origin/main:firmware/src/sketch.ino` và `firmware/include/control_core.h` tại commit 0c9cd10. Không sửa firmware hay infra/mosquitto.

| Nội dung | Firmware đang có | Tác động backend |
|---|---|---|
| Command | command / command_ack; command_id UUID; action, actuator, expiresAt; targetMode hoặc targetState chuỗi ON/OFF; chỉ FAN | REST boolean phải map ON/OFF ở transport, không đổi enum baseline; chưa gửi lệnh cho actuator khác firmware chưa hỗ trợ |
| ACK | APPLIED/REJECTED; duplicate trả DUPLICATE + originalStatus/originalReason; timestamp có thể null | Cần chốt replay ACK khi ACK gốc mất, mapping timeout/expiry; gpio_state chỉ report GPIO |
| Config | cfg / cfg_ack; version, humidity_on/off, temp_min/max, telemetry_interval_ms | ms khác REST/SQL giây; chưa hỗ trợ đầy đủ CO2/light/hysteresis trong V3 |
| ACK config | APPLIED, REJECTED, STALE, ALREADY_CURRENT | ALREADY_CURRENT chỉ kiểm version, không so nội dung; default firmware version1 có thể khác seed DB version1 |
| Events | events; device_id, event_type, sensor, reason, timestamp; SENSOR_FAULT/SENSOR_RECOVERED | Đã subscribe/log structured cho SENSOR_FAULT/MANUAL_LIMIT theo envelope hiện có; MANUAL_LIMIT producer chưa thấy trong firmware |

B2-12 đã thêm DeviceEvents service + một nhánh subscribe/dispatch trong gateway; giữ nguyên telemetry_ack/retry. Validate source device/topic/House/Zone, ESP32 enabled, active ancestors, timestamp, type và kích thước fields; JSON-escape strings khi log. Không INSERT anomaly_events/alerts/device_events, không phát alert production. Test sử dụng MQTT broker thật và **payload mô phỏng**, không chứng minh firmware gửi MANUAL_LIMIT; test retained flag qua gateway trực tiếp. Không có event_id nên không tuyên bố dedup/persist sự kiện. Timestamp rỗng khi chưa NTP và SENSOR_RECOVERED ngoài scope hiện bị từ chối.

## Câu hỏi ngắn gửi leader — đề xuất, chưa duyệt

1. **Timeout/expiry/ACK muộn:** chốt FAILED + failure_reason=ACK_TIMEOUT sau 10s từ sent_at; EXPIRED cho lệnh hết hạn trước publish; late ACK giữ terminal state và chỉ log? Firmware REJECTED/EXPIRED giữ REJECTED hay map EXPIRED? Giữ enum baseline, không thêm TIMEOUT.
2. **Command adapter:** xác nhận command/command_ack và targetState ON/OFF, expiresAt; tạm giới hạn FAN? Với DUPLICATE có originalStatus=APPLIED, có cho hoàn tất ACKNOWLEDGED khi ACK đầu mất không?
3. **Config:** chấp nhận cfg/cfg_ack và ms↔giây? Sửa firmware để ALREADY_CURRENT kiểm nội dung/hash, xử lý default version1 lệch seed1 thế nào? Các field CO2/light/hysteresis chưa được firmware áp dụng phải trả unsupported hay chỉ lưu DB?
4. **Schema sync/events:** duyệt per-device desired/applied version và stable event_id/retention của device_events khi mở lại migration; hiện chưa tạo V4. Xác nhận payload/producer MANUAL_LIMIT; có nhận SENSOR_RECOVERED và timestamp chưa đồng bộ không?
5. **Seed và quyền:** seed compatible tạo notifications bằng CROSS JOIN mọi active user với mọi alert, chưa scope membership. Seed baseline lookup House theo name không unique; không bảo đảm numeric ID cũ. Chỉ dùng test/demo hay cần bản seed được review trước môi trường chia sẻ? CRUD House/lifecycle và aggregation HOURLY/DAILY/giao ngày vẫn cần quyết định như handoff.

Global ADMIN truy cập toàn hệ thống đã được người dùng chốt, không còn là câu hỏi mở. B2-15/B2-16 vẫn do người khác phụ trách; bàn giao thêm ACL SUB events cho backend/PUB events cho đúng device. Không thay ACL/auth broker và không thay telemetry ACK contract.
