> Đã fast-forward tới origin/htran và test lại đúng staged source ngày 04/11/2026: [xác minh trước push](build2-push-verification.md). [Bảng sáu task](build2-resume-review.md) giữ checklist còn thiếu. V3 có trong source và DB test; không migrate DB nghiệp vụ.

# BUILD 2 — triển khai và bàn giao trên htran

Rà soát mới nhất: 04/11/2026. Đây là kết quả từng phần, **không phải nghiệm thu toàn bộ BUILD 2**. Tiêu chí là yêu cầu người dùng và các quyết định bổ sung ngày 02/11/2026; các đề xuất chưa được duyệt không được coi là baseline đã chốt.

## Trạng thái task

| Task | Trạng thái | Đã có / còn thiếu |
|---|---|---|
| B2-14 | Partial | Access dùng chung REST/WS, config và control reads; Global ADMIN active được truy cập mọi House active theo quyết định người dùng, House ADMIN chỉ trong House, VIEWER chỉ đọc, inactive account/membership bị từ chối. Chưa có command dispatch để kiểm quyền xuyên luồng đó |
| B2-10 | Partial | Đã GET actuator và GET command từ V3 theo quyền House. POST command/MQTT publish/ACK/timeout chưa triển khai vì mapping timeout/expiry, ACK muộn/DUPLICATE và hỗ trợ actuator của firmware còn chờ leader |
| B2-11 | Partial | GET/PUT lưu config V3, validate NFR/thresholds, optimistic version, no-op không tăng version. Luôn syncStatus=UNKNOWN; chưa publish retained cfg hoặc persist per-device ACK vì schema/proof-of-application còn chờ duyệt |
| B2-12 | Partial | Đã subscribe events và log JSON sau validate source/topic/type/timestamp. Không persist/alert. MANUAL_LIMIT dùng producer mô phỏng; firmware producer/schema event_id chưa chốt |
| B2-13 | Partial | Giữ ingest, thêm khóa đồng bộ inactive state và GET CountLog để catch-up; natural key V2 đối chiếu seed. Realtime mới sau commit vẫn giữ từ BUILD1. Chưa triển khai thống kê hôm nay vì chính sách HOURLY/DAILY/overlap/giao ngày chưa chốt |
| B2-17 | Partial | House tạo nguyên tử kèm initial Zone/membership (Global ADMIN), đọc/list, PATCH/disable/re-enable (Owner hoặc Global ADMIN); Zone/Device CRUD và soft disable đã có. Global OWNER tạo House và House ADMIN sửa House vẫn chưa được cấp khi policy chưa chốt |

Các mục chưa triển khai ở trên đều **Not Tested** về chức năng. Không tính skipped test là pass. B2-15/B2-16 ngoài phạm vi, không triển khai thay.

## API hiện thực trên schema V1/V2/V3

| Method/path | Hành vi |
|---|---|
| GET /api/v1/houses | Global ADMIN thấy các House active; người dùng khác cần active membership. Account inactive trả 403; page/size như BUILD1 |
| GET /api/v1/houses/{houseId} | Đọc theo HouseRole |
| GET /api/v1/houses/{houseId}/zones | page/size; trả cả Zone inactive để người quản lý biết và có thể re-enable |
| POST /api/v1/houses/{houseId}/zones | House OWNER/ADMIN; name, floorLabel nullable, areaM2 hữu hạn >=0; 201 |
| GET /api/v1/zones/{zoneId} | Zone/House active, mọi HouseRole hợp lệ được đọc |
| PATCH /api/v1/zones/{zoneId} | House OWNER/ADMIN; name, floorLabel, areaM2, active. Field vắng giữ nguyên, null chỉ cho floorLabel/areaM2. Cho phép re-enable Zone khi House còn active |
| POST /api/v1/zones/{zoneId}/devices | OWNER/ADMIN, active House/Zone; code không có whitespace hoặc /+#, name, type ESP32/CAMERA, enabled mặc định true. 201 hoặc 409 DEVICE_IDENTIFIER_EXISTS |
| PATCH /api/v1/devices/{deviceId} | OWNER/ADMIN, active House/Zone; chỉ name/enabled. Tắt enabled cũng đặt online=false; giữ lastSeenAt/lịch sử. Không đổi deviceId/type/zoneId |
| GET /api/v1/devices/{deviceId}/count-logs | READ theo House, from/to bắt buộc. periodStart trong [from,to), order periodStart DESC,id DESC; page=0,size=20,max100. Trả các window gốc, không cộng chúng |
| POST /api/v1/houses | Global ADMIN active; body theo OpenAPI có initialZone bắt buộc; House/Zone/membership OWNER của người tạo commit cùng transaction. Không tạo config/actuator hay seed ngầm |
| PATCH /api/v1/houses/{houseId} | House OWNER hoặc Global ADMIN; name/address/description/active. Không xóa/cascade; cho re-enable House inactive qua cùng quyền, không tự enable/disable flags của Device |
| GET /api/v1/zones/{zoneId}/config | House READ; 404 nếu chưa có row; trạng thái áp dụng UNKNOWN |
| PUT /api/v1/zones/{zoneId}/config | House WRITE; version=0 để tạo, version hiện tại để thay thế toàn bộ config. Chỉ lưu DB, không publish, syncStatus=UNKNOWN |
| GET /api/v1/devices/{deviceId}/actuators | House READ; page/size và thứ tự id tăng dần; trường on là DB report, không chứng minh relay vật lý |
| GET /api/v1/commands/{commandId} | House READ qua Actuator→Device→Zone→House; tra command_id chuỗi, trả status đang lưu, không tự expire/update trạng thái |

Các API BUILD1 giữ nguyên. `x-build104-implemented` ghi phạm vi lịch sử; `x-build2-implemented: true` đánh dấu 15 operation REST mới. Config có `x-build2-config-sync-implemented: false` để phân biệt lưu DB với sync. Không có DELETE vật lý. READ device disabled vẫn xem lịch sử nếu House/Zone active; ingest đòi enabled và active House/Zone. Các PATCH/POST quản lý dùng khóa hàng; config có version công khai, CRUD khác chưa có ETag/version.

Account/global role đọc từ DB; Global ADMIN active được vượt House, không tin role client đưa trong JWT. Khi quyền bị thu hồi, REST kiểm trạng thái hiện tại; WS chặn delivery tiếp theo và đóng 1008, chưa có timer đóng socket idle. Chưa phát WS riêng cho PATCH quản lý; client refresh bằng REST. House inactive bị ẩn khỏi list/GET thông thường; Owner/Global ADMIN có thể re-enable bằng PATCH ID đã biết. Quyền xem lịch sử House inactive và quyền House ADMIN sửa House vẫn cần leader chốt.

## Database và CountLog

Xem [baseline và đề xuất schema](../design/erd/build2-schema-proposal.md). V3 là nguyên bản nhóm, V1/V2 không đổi; không tạo V4. Chỉ dùng DB test riêng, không migrate/seed DB nghiệp vụ. Hai SQL reference cũ và hai seed mới được giữ nguyên, không tự import.

### Quy tắc config đã hiện thực

PUT là replace: các ngưỡng nullable bỏ qua trong request sẽ được lưu null, không phải PATCH. Validate hữu hạn; humidity 0..100 và on<off; nhiệt -10..60 và min<max; CO2 0..5000, light 0..100000 và min<=max; tempHysteresis>0 nếu có; telemetryIntervalSec 1..60; heartbeatIntervalSec=2. Cặp ngưỡng chỉ kiểm thứ tự khi cả hai khác null, đúng nullable contract hiện tại.

Khóa House rồi Zone để serialize với disable/PUT khác. Tạo row chỉ chấp nhận version=0, row bắt đầu version1. Update phải khớp version; thay đổi hợp lệ tăng đúng một lần; payload không đổi với version hiện tại giữ cả version và updatedAt. Stale request trả 409 VERSION_CONFLICT kể cả cùng nội dung. Không retry bằng cách tự tăng version. Chưa nhận cfg_ack hoặc tăng revision để xử lý STALE. Firmware default version1 có thể khác seed DB version1 nên chưa coi ALREADY_CURRENT là bằng chứng áp dụng.

Seed reference có 250 CountLog: 243 HOURLY, 7 DAILY; kiểm tra offline không có key trùng `(device_id,aggregation_type,period_start,period_end)`. Reference schema chưa có unique key này; V2 đã có. Không nhập reference vào V2 để thử. Retry ingest giữ counts và processedAt thì 200/cùng ID; khác payload cùng key 409; mới 201, realtime chỉ mới sau commit (test BUILD1).

Thống kê hôm nay phải dùng `Asia/Ho_Chi_Minh`: từ local date atStartOfDay tới ngày tiếp theo, đổi hai mốc sang Instant, khoảng [start,end). **Cần quyết định**: HOURLY-only hay ưu tiên DAILY nếu đủ ngày; window giao ngày có chia tỷ lệ hay từ chối; overlap cùng aggregation có được ingest hay không; nhiều camera trong một Zone có cùng quan sát một đàn hay cộng được. Đề nghị không cộng HOURLY+DAILY, không prorate số chim khi thiếu dữ liệu thời điểm. Hiện chỉ trả raw windows; không có endpoint tổng hôm nay dễ gây double count.

## Ma trận bàn giao MQTT/ACL — phần mới là đề xuất

Prefix `swiftletcare/{housePk}/{zonePk}/{deviceCode}`. Topic chính xác và payload mới cần P1/P4 xác nhận trước khi người làm B2-16 thêm ACL.

| Suffix | Backend | Device | QoS/retain | Trạng thái |
|---|---|---|---|---|
| telemetry | SUB | PUB | 0/false | Giữ nguyên BUILD1; B2-15 phụ trách ACK/retry |
| heartbeat | SUB | PUB | 0/false | Giữ nguyên |
| telemetry_ack | PUB | SUB | 1/false | Giữ nguyên payload/logic |
| command | PUB | SUB | đề nghị 1/false | Tên có trong offline design; payload/ACL chưa chốt |
| command_ack | SUB | PUB | firmware hiện 0/false | Đã thấy trong origin/main, backend chưa subscribe vì lifecycle còn cần chốt |
| cfg | PUB | SUB | đề nghị 1/true | Firmware dùng cfg; backend REST chỉ lưu DB, chưa publish |
| cfg_ack | SUB | PUB | firmware hiện 0/false | APPLIED/ALREADY_CURRENT/STALE/REJECTED; backend chưa nhận/persist |
| events | SUB | PUB | firmware 0/false, backend subscribe 1 | Structured log đã có; chưa có stable event_id |

Không thêm ACL vào infra/mosquitto. Gateway chỉ thêm events, không đổi telemetry ACK/retry; phiên tiếp tục này không sửa gateway. P1/P4 cần chốt các điểm trong [V3 integration](build2-v3-integration.md#câu-hỏi-ngắn-gửi-leader--đề-xuất-chưa-duyệt). B2-16 cấp PUB/SUB đúng House/Zone/device; topic khớp payload không thay thế broker authentication.

`application.yml` đã hỗ trợ MQTT_USERNAME/MQTT_PASSWORD, không cần sửa. `.env.example` thêm tên hai biến rỗng; Compose backend chuyển tiếp hai biến. Khi bật broker auth, người B2-16 cần phối hợp sửa healthcheck broker (hiện anonymous publish topic health), mount credential/ACL và script MQTT hiện có; phần đó chưa làm/chưa test. Test BUILD2 dùng broker riêng không lấy config infra, anonymous chỉ dành local test, không phải bằng chứng đạt B2-16. Chạy JAR trực tiếp trên Windows cần tự export hai biến từ môi trường, Spring không tự đọc .env.

## Windows và actual results

Từ repository root, với Docker Desktop Linux containers, Maven, Java 25 theo POM hiện có:

```powershell
./scripts/test-build2.ps1 -IsolatedBuild -TestDatabase build2_v3_review_20261102
# Máy chỉ có Java 21: kiểm tra tương thích riêng, không thay baseline Java 25
./scripts/test-build2.ps1 -Java21Compatibility -Offline -IsolatedBuild -TestDatabase build2_v3_review_20261102
```

Script dùng project `swiftletcare-build2-test`, PostgreSQL host 55433, MQTT 51884. DB review ở lệnh trên đã được tạo riêng trong phiên V3; nếu không truyền TestDatabase thì dùng DB test mặc định build2_test. Chạy clean verify và từ chối report có skipped test; IsolatedBuild tránh IDE ghi đè class Java25 khi Maven chạy Java21. Offline cần cache có sẵn. Env được khôi phục khi kết thúc; container/volume giữ nguyên. Không chạy suite với DB nghiệp vụ. Nếu cần dừng:

```powershell
docker compose -p swiftletcare-build2-test -f compose.build2-test.yml stop
```

Đã chạy thực tế PostgreSQL 17, Mosquitto 2, Spring Boot 4.0.8, Maven 3.10.0, Java21. Các lượt trước override Java21 vì POM local Java25; lượt sau fast-forward dùng POM Java21 xuất từ Git index, đúng baseline định commit. Nâng Java25 local giữ ngoài commit. HTTP/WS/SQL/MQTT dùng thành phần thật; producer mô phỏng, không có ESP32/relay vật lý.

Kết quả clean verify mới nhất: **27 tests, failures=0, errors=0, skipped=0**: BackendIntegrationTest 2; IngestionIntegrationTest 13; Build2ManagementIntegrationTest 11; ZoneConfigValidationTest 1. Ngoài regression và các tiêu chí đã kiểm ở phiên trước, test mới chứng minh CountLog commit/rollback trước realtime, retry không phát trùng, cross-House REST/WS bị chặn. Test threshold là unit test; HTTP/WS/MQTT/SQL dùng thành phần thật, producer MQTT/AI là mô phỏng, chưa tích hợp firmware/React thật. Script bắt buộc đủ bốn suite, không cho report rỗng/fail/skip và lưu summary mỗi lần chạy.

Lần chạy sau fast-forward kết thúc **17:23:15 ngày 04/11/2026 (Asia/Saigon)**, BUILD SUCCESS, vẫn 27/27 và không skip. Validator OpenAPI nội bộ chỉ kiểm YAML/refs/operationIds/parameters/responses/security, không thay full official validator. `git diff --cached --check` được kiểm lại.

Log sau fast-forward: `backend/logs/build2-post-ff-verify.log`; staged source snapshot/verification-summary/JUnit/JAR: `backend/logs/staged-build/20261104-172143/`. Lượt trước lưu ở `backend/logs/isolated-build/20261104-165808-098/`. BUILD1 regression không có nghĩa đã chạy lại recovery smoke/restart, performance NFR hay firmware thật. Java25 build, Docker image, frontend demo, command dispatch/ACK, retained config/device ACK, event persistence và thống kê hôm nay: **Not Tested**.

Backup trước phiên tiếp tục: `backend/logs/backups/20261102-213024-before-build2-continuation/`, ZIP 33 file cùng patch/hash. Không thay nội dung V1/V2/V3, seed, firmware, infra/mosquitto hoặc gateway trong phiên này.

## File thay đổi

- Security/API: `security/Access.java`, `security/SecurityConfig.java`, `api/DeviceController.java`, mới `management/ManagementController.java`, mới `ai/CountReadController.java`, `ai/CountLogRepository.java`.
- Ingest: `iot/Ingestion.java`, `ai/CountController.java` chỉ thêm khóa/kiểm tra enabled + ancestor active; không sửa MQTT telemetry_ack/retry.
- Bổ sung trong phiên tiếp tục: `management/ZoneConfigController.java`, `iot/ControlReadController.java`; mở rộng `management/ManagementController.java`, `security/Access.java`, `Build2ManagementIntegrationTest.java`; mới `management/ZoneConfigValidationTest.java` trong test source. Chỉ dùng bảng V3 hiện hữu.
- Test/nền trước đó giữ nguyên: `compose.build2-test.yml`, `scripts/build2-test-mosquitto.conf`, `scripts/test-build2.ps1` và suite BUILD1.
- Env/docs: `.env.example`, `docker-compose.yml` (chỉ forward credential backend), `backend/README.md` (thêm mục BUILD2), `docs/api/openapi.yaml`, `docs/api/realtime-contract.md`, tài liệu này và schema proposal, `docs/mqtt/topics.md` (link phối hợp).

Trước phiên đã có thay đổi ở backend/Dockerfile, backend/README.md, backend/pom.xml, docs/requirements/scope.md và `.vscode/`; giữ nguyên. Không commit/push/merge, không thay nhánh, không reset/import DB, không đổi seed ID. Chưa có review/demo/sign-off hoặc Jira Bug link được tạo.
