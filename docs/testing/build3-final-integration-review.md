# SwiftletCare — Build 3 Final Integration Review

**25/11/2026 · `AI-b3` · HEAD `3a146bc` và thay đổi của lượt rà soát.** `SWIFTLETCARE.docx` ghi nhánh `be-b3`. Lượt này dùng nhánh đang mở để giữ phần AI đã tích hợp và các artifact local có trước trong `backend/target`. Phạm vi theo `SWIFTLETCARE.docx`, với `B3.31.docx`, `B3.32.docx`, `3.33.docx` và `3.35.zip` làm nguồn đối chiếu.

**BUILD 3 chưa đủ bằng chứng nghiệm thu toàn hệ thống.** Đã sửa một lỗi runtime: firmware chặn `SENSOR_RECOVERED` trước khi enqueue MQTT, trong khi Backend có xử lý phục hồi. Regression Backend vẫn **76/76 PASS, 0 failure, 0 error, 0 skip** với `DB_TEST_ENABLED=true`. Trong 9 nhiệm vụ được rà soát dưới đây, 7 PARTIAL, 1 BLOCKED và 1 PASS trong phạm vi mô phỏng. [Handoff P4 trước lượt sửa](build3-backend-handoff.md) · [Log verify sau lượt sửa](../../firmware/.pio/build3-final-review/backend-verify.log)

## Trạng thái P1 và P3

| Task | Hiện trạng | Vấn đề | Hành động | Test evidence | Kết luận |
| --- | --- | --- | --- | --- | --- |
| B3-31 — Ngưỡng nhiệt độ và CO₂ | AUTO dùng hysteresis. Một phần Zone Config có đồng bộ. | Chưa duyệt ngưỡng. `tempMax` đi tới thiết bị nhưng FAN vẫn dùng 31/30 °C cố định. | Đối chiếu điều kiện, trường MQTT và giới hạn của ACK. Giữ nguyên ngưỡng prototype. | Backend config ACK 1/1. Firmware build và đối chiếu mã. Chưa đo chuyển ngưỡng trên thiết bị. [Bảng đối chiếu](#b3-31--ngưỡng-fanheater) | **PARTIAL / PENDING APPROVAL** |
| B3-32 — Phần cứng | Ba đầu vào và bốn LED Wokwi khớp GPIO của sketch. | Chưa đo thiết bị, tiếp điểm hoặc fail-safe khi tải thật. | Ghi sơ đồ, BOM, nguồn giá và các phép thử bàn điện áp thấp. | Đối chiếu 7 GPIO và compile `esp32dev`. [Wiring](../hardware/wiring.md) · [BOM](../hardware/bom.md) | **PARTIAL / NOT PHYSICALLY VERIFIED** |
| B3-33 — Soak 120 phút | Tài liệu mô tả 21 phút 06 giây có đổi mode. | **MISSING EVIDENCE** cho serial/MQTT gốc và 120 phút AUTO. Hai chỗ ghi heap khác nhau. | Bỏ checkpoint giả định, ghi số liệu theo bảng của tệp nguồn và thêm quy trình cùng script. | Kiểm tra script bằng dữ liệu tự tạo. Chưa chạy soak 120 phút. [Soak evidence](../tests/firmware-soak-build3.md) | **PARTIAL** |
| B3-35 — PID và rule-based | Hai nhánh dùng cùng mô hình mô phỏng. | Mô hình đơn, CO₂ cố định, chưa có dữ liệu tải thật. | Tái chạy mô phỏng, đối chiếu toàn bộ CSV và ghi trade-off. | 121/121 dòng trùng. [Kết quả so sánh](../iot/pid-comparison.md) | **PASS trong phạm vi mô phỏng** |
| B3-20 — Statistics/Count Log | Backend đọc cửa sổ CountLog. Audit xác minh Entry từng ngày theo giờ Việt Nam. | FE Reports dùng dữ liệu dựng sẵn. **MISSING EVIDENCE** cho báo cáo 7 ngày và CSV từ dữ liệu thật. | Xác định semantics lịch sử, chống cộng chồng và các điều kiện nối FE. | Daily Entry Audit 5/5. [CountReadController](../../backend/src/main/java/com/swiftletcare/ai/CountReadController.java) · [FE Reports](../../frontend/src/pages/Reports.tsx) | **PARTIAL** |
| B3-21 — Integration Regression | Backend test với PostgreSQL và Mosquitto riêng. AI unit/integration tests và FE build chạy được. | **MISSING EVIDENCE** cho bốn luồng xuyên hệ thống đến trình duyệt. | Sửa luồng recovery và viết các bước tái kiểm thử từ nguồn tới giao diện. | Backend 76/76, AI 19/19, FE build. [Kết quả chạy](#kết-quả-kiểm-thử) | **PARTIAL** |
| B3-22 — NFR | Có phép đo lịch sử BUILD 2, benchmark SQL B3-17 và thời gian Camera Offline ở Backend. | **MISSING EVIDENCE** cho tải, soak và độ trễ tới giao diện BUILD 3. Alerts hiện dùng dữ liệu cục bộ. | Giữ số đo theo từng phạm vi, lập kế hoạch đồng hồ và ID để đo lại. | Camera Offline 30,086 s tại DB và 30,099 s tại House WS. Benchmark SQL có số đo riêng. [NFR](../requirements/NFR.md) · [Benchmark B3-17](build3-backend-handoff.md#b3-17--benchmark-thực-tế-và-dry-run) | **BLOCKED** đối với nghiệm thu NFR xuyên hệ thống |
| B3-23 — API Contract/Traceability | OpenAPI `0.9.0-review`, validator nội bộ pass. | Draft lệch REST/payload thực tế ở nhiều nhóm. **MISSING EVIDENCE** cho Contract v1.2 đã phê duyệt và traceability đầy đủ của P3. | Ghi chênh lệch theo route và nối tiêu chí với test hiện có. | 47 operations trên 36 paths qua validator cấu trúc. [Đối chiếu API](#rest-websocket-và-openapi) | **PARTIAL** |
| B3-24 — Triage, risk, freeze | Đã ghi lỗi, rủi ro và nhóm cần phối hợp trong lượt rà soát. | **MISSING EVIDENCE** cho quyết định feature freeze và xác nhận các hạng mục mở. | Đưa ra thứ tự đóng điều kiện nghiệm thu, theo nhóm xử lý. | Sửa recovery có regression. Các quyết định ghi dưới đây chưa được phê duyệt. [Điều kiện chốt BUILD 3](#điều-kiện-chốt-build-3) | **PARTIAL** |

`MISSING EVIDENCE` là kết quả tìm bằng chứng trong repo và tệp cung cấp cho lượt này. JIRA được dùng qua phạm vi ghi trong tài liệu đã gửi và cần đối chiếu trạng thái trên board khi chốt.

## B3-31 — Ngưỡng FAN/HEATER

**PENDING APPROVAL.** `B3.31.docx` cung cấp các mốc prototype. FR-IOT-005 và FR-IOT-007 yêu cầu cấu hình môi trường và điều khiển tự động. Các mốc 26, 28, 31, 30 °C và 1.000/800 ppm vẫn chờ xác nhận áp dụng. [FR-IOT](../requirements/FR.md) · [Mã firmware](../../firmware/src/sketch.ino) · [Control core](../../firmware/include/control_core.h)

| Quy tắc | Firmware thực tế | Backend và MQTT | Database/API |
| --- | --- | --- | --- |
| MIST | `humidityOn`/`humidityOff` được dùng trong AUTO khi DHT hợp lệ. | Đồng bộ `humidity_on`/`humidity_off` khi cấu hình tương thích. Engine đánh giá môi trường theo cấu hình Zone. | `zone_configs.humidity_on/off`, `humidityOn/Off` trên cấu hình Zone. |
| HEATER ON dưới 26 °C prototype | ON dưới `zoneConfig.tempMin` khi FAN đang tắt. Giá trị mặc định là 26 °C. | `temp_min` được gửi qua MQTT và áp dụng cho điều kiện bật. | `zone_configs.temp_min`, REST `tempMin`. |
| HEATER OFF từ 28 °C hoặc FAN ON | Cố định `>=28 °C` hoặc FAN đang bật. | Chưa có trường riêng cho mốc tắt HEATER trong payload đồng bộ. | `tempHysteresis` có lưu trong Zone Config nhưng hiện diện của trường làm bản cấu hình không đủ điều kiện sync hiện tại. |
| FAN theo nhiệt độ | ON ở `>=31 °C`. OFF cần `<=30 °C` và CO₂ hợp lệ `<800` ppm. | Gửi `temp_max` và firmware nhận/lưu, nhưng điều khiển FAN dùng hằng số. ACK xác nhận phiên bản cấu hình đã nhận, không chứng minh FAN dùng giá trị đó. | `tempMax` được lưu DB và Backend có thể dùng làm ngưỡng cảnh báo môi trường. |
| FAN theo CO₂ | ON với CO₂ hợp lệ `>1.000` ppm. Khi CO₂ thiếu hoặc lỗi, nhiệt độ vẫn có thể bật FAN. Nhánh AUTO tắt FAN cần CO₂ hợp lệ. | `co2_min`/`co2_max` dùng trong đánh giá môi trường ở Backend. Payload cấu hình ESP32 hiện không chứa hai trường. | `co2Min`/`co2Max` lưu tại `zone_configs`; đơn vị ppm. |
| Chu kỳ | Chu kỳ telemetry firmware thay đổi được. | `telemetry_interval_ms` lấy từ `telemetry_interval_sec`; heartbeat phải là 2 s để đủ điều kiện đồng bộ. | REST/DB dùng giây. |

`CONFIG_SYNC_ENABLED` mặc định `false`. Khi bật, phiên bản tương thích phải có đủ `humidity_on/off` và `temp_min/max`, còn `temp_hysteresis`, `co2_min/max`, `light_min/max` phải null. Các trường hiện có trên REST và DB rộng hơn hợp đồng ESP32. `control_core.h` chỉ mô hình hóa phần ngưỡng FAN nhiệt độ 31/30, nên kết quả từ helper này không xác minh nhánh CO₂ của sketch. [Controller Zone Config](../../backend/src/main/java/com/swiftletcare/management/ZoneConfigController.java) · [DeviceConfigSync](../../backend/src/main/java/com/swiftletcare/iot/DeviceConfigSync.java) · [Schema V3](../../backend/src/main/resources/db/migration/V3__add_missing_erd_tables.sql) · [Alert Engine](../../backend/src/main/java/com/swiftletcare/alert/AlertEngine.java)

## Lỗi đã sửa

`publishSensorEvent` từng thoát ngay khi gặp `SENSOR_RECOVERED`. Sau lần đọc DHT hợp lệ thứ ba theo điều kiện hiện có, firmware giờ gửi sự kiện lên `swiftletcare/{house}/{zone}/{device}/events` với `sensor=DHT22` và `reason=THREE_GOOD_READINGS`. Regression qua Mosquitto kiểm tra sự kiện vào Backend, cập nhật `cleared_at` đúng Alert, giữ `NEW` và giữ một dòng audit. Điều kiện môi trường phục hồi và workflow xử lý Alert là hai trạng thái độc lập. Firmware đã compile, còn việc quan sát gói tin phát từ ESP32/Wokwi nằm trong E2E cần chạy. [Firmware](../../firmware/src/sketch.ino) · [Regression MQTT](../../backend/src/test/java/com/swiftletcare/IngestionIntegrationTest.java) · [Hợp đồng topic](../mqtt/topics.md)

## REST, WebSocket và OpenAPI

| Nhóm | Backend/Frontend đang có | Sai lệch hoặc điều kiện tích hợp |
| --- | --- | --- |
| Auth, Profile, Logout, Account | Login `POST /api/v1/auth/login`; hồ sơ `GET/PATCH /api/v1/me`; Logout tăng `token_version`, JWT cũ bị từ chối 401. `GET/POST /api/v1/admin/users` và `PATCH .../{id}/active` yêu cầu Global ADMIN. | Draft ghi `/api/v1/users/me`, refresh endpoint chưa có controller. Cần khớp payload logout và hành vi 401 của JWT hết hạn, bị thu hồi hoặc user inactive. |
| House membership | `PATCH /api/v1/houses/{houseId}/members/{membershipId}` đổi `OWNER`, `ADMIN`, `VIEWER`. Quyền House được đọc lại với JWT hiện có. | Body `role` đã khớp PATCH draft. GET/POST members vẫn ở mức thiết kế. 403 cho scope sai hoặc vai trò không đủ. |
| Telemetry/Heartbeat | Device REST có latest và lịch sử. Ingestion nhận MQTT kèm `device_id`, `event_id` với telemetry. `STORED` tương ứng bản ghi đã được lưu. Dashboard đọc REST và mở Device WS. | ESP32 heartbeat là MQTT, AI Camera heartbeat là HTTP. Cần thể hiện vai trò của từng giao thức trong contract FE. |
| AI Snapshot/Event/Heartbeat | `POST /api/ai/snapshots` multipart; `POST /api/ai/events` và `POST /api/ai/heartbeat` dùng `X-Service-Key`. `GET /api/snapshots/{id}` kiểm tra JWT và House. | Draft dùng `POST /api/v1/internal/ai/anomalies`; các route đọc anomalies trong draft chưa có controller tương ứng. Luồng FE chưa được nghiệm thu. |
| Alert REST/workflow | List lọc theo status, level, Zone. `NEW → IN_PROGRESS → RESOLVED`; chuyển ngược trả 409; audit actor/thời gian. | Draft list thiếu `level` và khai ID int32. History thực tế là mảng với `changedAt`; draft dùng `{items,page}` và `changeAt`. FE Alerts hiện dùng state cục bộ và dữ liệu mẫu. |
| WebSocket | `/ws/houses/{houseId}` phát `alert.*` và `camera.status.changed` theo House. Device WS dùng `/ws/devices/{id}`. Đọc lại quyền và đóng 1008 khi không còn quyền đọc. | Ghi đúng `data.id`, schema từng event và quy tắc reconnect/xác thực trước khi sinh client hoặc nối Alert Center. |
| Count Logs/Statistics/CSV | `POST /api/v1/internal/ai/count-logs`; `GET /api/v1/devices/{deviceId}/count-logs` đọc cửa sổ raw theo `periodStart ∈ [from,to)`, lọc scope House. | Reports của FE đang xuất CSV mẫu. Draft còn route report/dashboard chưa có Backend tương ứng. Cần chốt quy tắc tổng hợp trước khi nối bảng và CSV. |

Daily Entry Audit dùng ngày lịch `Asia/Ho_Chi_Minh`, tách HOURLY và DAILY, xác minh độ phủ từng series và không cộng Entry qua hai độ phân giải. Raw CountLog tách `entryCount` và `exitCount`. Trước khi nghiệm thu thống kê cần test ngày vắt qua 00:00 giờ Việt Nam, cửa sổ thiếu/chồng lấn, tách Entry/Exit, quy tắc đa camera và CSV khớp biểu đồ. Quy tắc FLOCK_DROP chờ P35/P36 và baseline. [Daily Entry Audit](../../backend/src/main/java/com/swiftletcare/reporting/DailyEntryAudit.java) · [OpenAPI draft](../api/openapi.yaml) · [Đối chiếu route và payload P4](build3-backend-handoff.md) · [Realtime notes](../api/realtime-contract.md)

## Bằng chứng E2E và kế hoạch chạy

| Luồng | Đoạn đã có bằng chứng | E2E và phép thử còn thiếu |
| --- | --- | --- |
| IoT — ESP32 → MQTT → Backend → PostgreSQL → WS → FE | Test broker/PostgreSQL xác minh ingest, ACK theo ID, dữ liệu lưu và Device WS. Dashboard có REST và WS thật trong mã. | **NOT TESTED** đến DOM Build 3. Chạy ESP32/Wokwi trên broker test và ghi cùng mã thiết bị/`event_id`, timestamp UTC của MQTT, DB, WS và DOM. Sau reconnect kiểm đếm lại ID và đối chiếu các chặng. |
| Alert — Telemetry/Event → Engine → Audit → WS → Alert Center | Sensor fault/recovery qua broker. Alert workflow 6/6, audit và cách ly House WS. | **NOT TESTED** đến Alert Center. Nối Alerts với REST và House WS thật. Dùng hai House để kiểm tra tạo Alert, workflow, audit, cách ly và cập nhật UI không tải lại. |
| AI — Camera → Snapshot/Event/Heartbeat → Alert → FE | AI 19/19; Backend chạy B3-12 4/4 và B3-14 5/5. Bằng chứng P2 chạy thử riêng và đánh giá dữ liệu. | **NOT TESTED** tới UI. Chạy AI với hai camera cùng Zone. Tạo predator event có snapshot; ngừng một heartbeat, đo offline trong 35 s đến mốc Backend/WS; phục hồi từng camera và kiểm tra UI cùng phạm vi House. Đo riêng AI event đến UI cho NFR. |
| Quyền — Login → JWT → Membership → REST/WS | B3-13 5/5, B3-16 7/7. Có HTTP với JWT cũ, WS cùng/sai House và cập nhật đồng thời trong test Backend. | **NOT TESTED** trong trình duyệt. Dùng hai phiên, đổi role bằng tài khoản quản trị và kiểm tra HTTP kế tiếp, quyền sửa sau khi hạ Viewer, đóng socket khi mất quyền đọc và logout. |

NFR BUILD 2 ghi TC-12 thiếu cặp WS/DOM khi Backend tiếp tục phát với `sessions=0`, và TC-20 còn ACK `INVALID_EXPIRY`. Các kết quả này là đầu vào retest. Các phép đo B3-14 là thời gian từ heartbeat cuối đến DB và House WS trong integration test. Mốc AI event đến thông báo trong NFR cần timestamp nhận tại UI/mobile, không thể suy ra từ thời gian scanner. [BUILD 2 NFR](build2-nfr-measurements.md) · [BUILD 2 retest](build2-retest-20261122.md) · [P2 AI eval v2](../ai/eval-v2.md)

## Kết quả kiểm thử

| Kiểm thử | Kết quả thực tế | Bằng chứng |
| --- | --- | --- |
| Backend trước lượt sửa | 76/76, 0 skip theo handoff P4 | [Handoff P4](build3-backend-handoff.md) |
| Backend sau lượt sửa | Maven `verify`, `DB_TEST_ENABLED=true`, PostgreSQL 18.6 và Mosquitto thử riêng. 76/76, 0 failure/error/skip. Trong đó Ingestion 13/13, Alert Engine 10/10 và Alert Workflow 6/6. | [Log Maven](../../firmware/.pio/build3-final-review/backend-verify.log) · [Surefire Ingestion](../../firmware/.pio/build3-final-review/maven-build/backend/target/surefire-reports/com.swiftletcare.IngestionIntegrationTest.txt) |
| Camera B3-14 | 5/5. 30,086 giây đến DB, 30,099 giây đến House WS từ heartbeat cuối. | [Log Maven](../../firmware/.pio/build3-final-review/backend-verify.log) · [Surefire Camera](../../firmware/.pio/build3-final-review/maven-build/backend/target/surefire-reports/com.swiftletcare.CameraHeartbeatIntegrationTest.txt) |
| Firmware | PlatformIO `esp32dev` PASS. RAM 56.136/327.680 byte, flash 837.465/1.310.720 byte. | [Log PlatformIO](../../firmware/.pio/build3-final-review/firmware-build.log) |
| AI | `pytest ai/tests` 19/19. | [Log pytest](../../firmware/.pio/build3-final-review/ai-pytest.log) |
| Frontend | Production build PASS. Vite báo chunk lớn hơn 500 kB. | [Log Vite](../../firmware/.pio/build3-final-review/frontend-build.log) |
| OpenAPI | Kiểm tra cấu trúc nội bộ PASS, 47 operations, 36 paths. | [Log validator](../../firmware/.pio/build3-final-review/openapi-check.log) |
| GPIO, script soak và PID | 7 GPIO khớp, script soak qua fixture tự tạo, PID tái lập 121/121 dòng. | [Log GPIO và script](../../firmware/.pio/build3-final-review/gpio-and-soak-tool.log) · [Log PID](../../firmware/.pio/build3-final-review/pid-run/pid-reproduction.log) |

Log lượt này lưu local trong `firmware/.pio/build3-final-review/`, là thư mục Git ignore. Lượt Maven dùng bản sao mã Backend trong thư mục này để bảo toàn `backend/target` đã có trước. Benchmark truy vấn B3-17 là kết quả lượt đo trước, gồm Count Logs trang đầu 0,729 → 0,198 ms và Alerts theo House trang đầu 54,413 → 0,322 ms median trên fixture. Diễn tập V9 → V11 và bằng chứng dry-run nằm trong handoff P4. [Benchmark, migration và dry-run](build3-backend-handoff.md)

## Các file đã đổi

| File | Nội dung |
| --- | --- |
| [firmware/src/sketch.ino](../../firmware/src/sketch.ino) | Cho `SENSOR_RECOVERED` đi qua hàng đợi publish hiện có. |
| [IngestionIntegrationTest.java](../../backend/src/test/java/com/swiftletcare/IngestionIntegrationTest.java) | Xác minh recovery qua MQTT, `cleared_at`, workflow, audit và chống trùng. |
| [docs/mqtt/topics.md](../mqtt/topics.md) | Cập nhật hợp đồng Device Event từ mô tả BUILD 2 sang luồng đang chạy. |
| [docs/hardware/wiring.md](../hardware/wiring.md), [docs/hardware/bom.md](../hardware/bom.md) | Bảng GPIO, linh kiện, giá có nguồn và giới hạn xác minh. |
| [docs/tests/firmware-soak-build3.md](../tests/firmware-soak-build3.md), [scripts/audit-firmware-soak.py](../../scripts/audit-firmware-soak.py) | Rà soát checkpoint và tổng hợp log serial/MQTT theo từng lượt boot. |
| [docs/iot/pid-comparison.md](../iot/pid-comparison.md) | Ghi phép tái lập và số đo đối chiếu. |
| [docs/testing/build3-final-integration-review.md](build3-final-integration-review.md) | Báo cáo trạng thái, hợp đồng và điều kiện nghiệm thu. |

## Điều kiện chốt BUILD 3

1. **P3/P4/P5:** Chốt payload Alert/History/House WS, nối Alert Center với dữ liệu thật và chạy E2E hai House. Nối Reports sau khi chốt nguồn CountLog, biên ngày, Entry/Exit, cách gộp camera và CSV. Lưu bằng chứng thực ở từng chặng và chạy lại các NFR còn thiếu.
2. **P1/P3:** Chạy soak 120 phút trên ESP32/Wokwi, lưu serial/MQTT gốc và thử fail-safe tại bàn thử. Xác nhận `SENSOR_RECOVERED` phát từ firmware sau ba lượt đọc tốt. Kiểm tra lại checkpoint heap 17:56/17:58 bằng log gốc nếu còn.
3. **P1/P4 và nhóm nghiệp vụ/giảng viên:** Duyệt ngưỡng prototype, hành vi CO₂ lỗi và nghĩa `tempMax` trong Zone Config, bao gồm báo trạng thái áp dụng thực tế cho người dùng.
4. **P2/P3/P4/P5:** Chạy camera/AI đến Alert Center, xem ảnh đúng House và đo NFR đến giao diện. Chốt Contract v1.2 và traceability trước feature freeze.
5. **P4 và nhóm nghiệp vụ/giảng viên:** Chốt chính sách P35/P36, baseline/ngưỡng FLOCK_DROP và quyền xóa/retention snapshot. Lên lịch nâng môi trường gốc sau khi kiểm tra V9, backup/restore và duyệt triển khai V10/V11. [Điều kiện triển khai P4](build3-backend-handoff.md)
