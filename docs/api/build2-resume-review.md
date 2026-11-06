# BUILD 2 — kiểm tra sau khi tiếp tục ngày 04/11/2026

> Báo cáo này ghi trạng thái trước bước fast-forward/commit được người dùng phê duyệt sau đó. Kết quả test trên đúng Git index sau tích hợp: [xác minh trước push](build2-push-verification.md). Các mô tả HEAD/untracked/không commit bên dưới là bằng chứng tại thời điểm rà soát, không phải trạng thái Git hiện tại.

## Trạng thái khôi phục đã xác minh

- Branch `htran`, HEAD `03fa14c52115d016fbceb83180748a78685fa78b` (`docs(db): add reference schema and seed SQL`). Các commit gần trước là d595775 (BUILD1 MVP), 6b77690 (backend/PostgreSQL). Không checkout/merge main, không commit/push/stage/reset hoặc xóa source.
- Đã đọc `git status --short -uall`, `git diff` của code/test đang sửa, các file mới, migration, OpenAPI, security matrix, handoff và schema proposal. Không có AGENTS.md trong checkout. Số file thay đổi không được dùng để kết luận task hoàn tất.
- Các controller mới, test BUILD2, V3 và hai seed mới vẫn là file **untracked**. Chúng cần được đưa vào thay đổi khi người dùng chuẩn bị commit; không chỉ lấy tracked diff rồi coi đã có đầy đủ BUILD2.
- Backup trước thay đổi phiên này: `backend/logs/backups/20261104-165628-resume-review/` (ZIP 36 file, patch, SHA256 manifest). Các thay đổi Dockerfile/POM/scope/.vscode và toàn bộ code đang làm từ trước được giữ nguyên.

## V3 trên filesystem và trên DB là hai kiểm tra riêng

1. Trong source có V1, V2 và `V3__add_missing_erd_tables.sql`. V3 đối chiếu nguyên byte với commit nhóm 080fbba, không tạo migration khác hoặc sửa V1/V2/V3. Hai seed mới giữ nguyên nội dung; phiên này không import seed.
2. SELECT trực tiếp `public.flyway_schema_history` trên **DB test** `build2_v3_review_20261102` cho thấy 1/2/3 success. SELECT information_schema trả 17 bảng `swiftletcare`. Regression cũng validate Flyway/JPA và migrate lại không có migration cần chạy.

DB test dùng container `swiftletcare-build2-test-postgres-1`, cổng host 55433; broker test cổng 51884. Không kết nối DB nghiệp vụ để migrate/seed/reset. Không xóa volume. Đây là kiểm tra mới ngày 04/11, không chỉ suy ra từ file V3 hoặc báo cáo ngày 02/11.

## Đối chiếu sáu task

| Task | Trạng thái | Đã có và bằng chứng mới | Còn thiếu / bị chặn |
|---|---|---|---|
| B2-10 ControlCommand | Partial | ControlReadController: GET actuator/command; test `actuatorAndCommandReadsRespectHouseScope` pass. Đọc giữ nguyên enum/status đã lưu, không khẳng định relay vật lý từ report | POST commands; Online/enabled/actuator checks khi tạo; publish non-retained; APPLIED/REJECTED handling; duplicate/early/late ACK, timeout/publish failure/concurrent transitions; WS sau commit cho command. Mapping timeout/expiry/DUPLICATE và FAN-only cần leader chốt; các luồng này Not Tested |
| B2-11 ZoneConfig | Partial | GET/PUT V3, House READ/WRITE, NFR finite/range/order; version 0 tạo, update CAS, no-op giữ version/updatedAt, hai writer chỉ một thành công. Ba test config trong Build2ManagementIntegrationTest và ZoneConfigValidationTest pass | PUT chỉ lưu DB và trả UNKNOWN. Retained publish sau commit, device/version ACK, STALE recovery và persist per-device sync chưa làm. V3 chưa có sync table, migration mới vẫn tạm dừng; đồng nhất payload/version1 của firmware chưa chốt. Transport Not Tested |
| B2-12 Device Events | Partial | Subscribe events, validate device/topic/House/Zone/type/timestamp, JSON log. `deviceEventsValidateSourceAndWriteStructuredLogsOnly` pass qua broker thật, không ghi anomaly/alert | Producer/payload MANUAL_LIMIT và stable event_id chưa chốt; persistence device_events chưa có schema duyệt. Test dùng payload mô phỏng; firmware MANUAL_LIMIT/React thật Not Tested |
| B2-13 CountLog/thống kê | Partial | Ingest env service key, CAMERA/ancestor checks, period/count/type validation, natural-key retry/conflict/concurrency; GET catch-up. Test mới `countRealtimeIsCommittedOnceAndCannotCrossHouse` pass: HTTP/WS/DB thật, commit thành công mới event, retry không event trùng, lỗi commit không row/event, retry sau lỗi thành công, user khác House bị chặn | Chưa có tổng hôm nay Asia/Ho_Chi_Minh. Cần chốt HOURLY/DAILY, overlap/giao ngày và nhiều camera cùng quan sát; không tự cộng hai aggregation. Thống kê hôm nay Not Tested |
| B2-14 Phân quyền | Partial | Global ADMIN active toàn hệ thống; House OWNER/ADMIN đúng House, VIEWER đọc; inactive account/membership, expiry, 401/403, cross-House, WS revoke và credential AI tách biệt được test. Authorization dùng chung Access trên REST/WS hiện có | Chưa kiểm end-to-end command dispatch vì B2-10 chưa có. Policy tạo House cho Global OWNER, House ADMIN sửa House và đọc lịch sử House inactive còn cần chốt. Không coi các API chưa tồn tại là đã bảo vệ/test xong |
| B2-17 CRUD | Partial | House tạo nguyên tử với Zone/membership, Owner/global ADMIN patch/disable/re-enable; Zone/Device create/read/list/patch/disable, giữ lịch sử và chặn reassignment. Test rollback tại commit, concurrent patch, reactivation và disabled ingest pass | House creation hiện Global ADMIN-only; House PATCH Owner/global ADMIN-only. Chưa mở các quyền House còn chưa chốt; disabled history chỉ đọc khi ancestors active. Không xóa vật lý/cascade/đổi seed ID |

Không có task nào được đánh dấu Done chỉ vì phần REST tương ứng pass. B2-15 telemetry ACK/retry và B2-16 broker authentication/ACL do người khác làm; phiên này không sửa production gateway, Ingestion, infra/mosquitto, application.yml hoặc Compose.

## Phần thực hiện tiếp trong phiên này

Không dựng lại các controller đã hoàn thành và không tự chọn policy/schema còn bị chặn. Đã bổ sung bằng chứng nghiệm thu còn thiếu, đủ thông tin của B2-13/B2-14:

- Test HTTP ingest + ba WebSocket connections: camera được cấp quyền, camera của House khác và tài khoản khác House cố subscribe camera đích. Socket bị từ chối đóng 1008; REST đọc khác House trả 403; chỉ socket đúng nhận ai.count.updated.
- Kiểm tra row đã commit bằng connection SQL riêng khi nhận event. Retry cùng payload không phát lại; payload khác cùng key trả 409 và không phát event mới.
- Deferred trigger chỉ trong DB test làm commit CountLog thất bại: 503, không row, không WS event. Gỡ đúng trigger/function fixture của test rồi retry thành công. Không để lại trigger test, không xóa dữ liệu có sẵn.
- Siết script xác minh: bắt buộc đủ bốn suite có tên cụ thể, có test, không fail/error/skip; lưu verification-summary.json theo source snapshot để giữ bằng chứng sau khi tắt máy. Không chấp nhận chỉ ba report bất kỳ hoặc số file thay đổi làm bằng chứng pass.

File thay đổi lần này: `backend/src/test/java/com/swiftletcare/IngestionIntegrationTest.java`, `scripts/test-build2.ps1`, tài liệu này, `docs/api/build2-handoff.md`, `backend/README.md`. Không thay code production hoặc migration để lách các blocker.

## Lệnh và actual results

```powershell
./scripts/test-build2.ps1 -Java21Compatibility -Offline -IsolatedBuild -TestDatabase build2_v3_review_20261102
```

Kết quả ngày 04/11/2026, kết thúc 16:59:45 Asia/Saigon: **BUILD SUCCESS, 27 pass / 0 failures / 0 errors / 0 skipped**.

| Suite | Pass | Fail/error/skip |
|---|---:|---:|
| BackendIntegrationTest | 2 | 0/0/0 |
| IngestionIntegrationTest | 13 | 0/0/0 |
| Build2ManagementIntegrationTest | 11 | 0/0/0 |
| ZoneConfigValidationTest | 1 | 0/0/0 |

Chạy PostgreSQL/Mosquitto/Spring HTTP/Java WebSocket thật. Producer MQTT và camera AI request là **mô phỏng**; không có ESP32/relay/React thật. ZoneConfigValidationTest là unit test. Regression BUILD1 nằm trong hai suite BackendIntegrationTest/IngestionIntegrationTest, không có test skip. Java21 compatibility, không thay POM Java25 của người dùng; build/runtime Java25, Docker image, recovery smoke, performance NFR và frontend/firmware end-to-end chưa chạy lại và **Not Tested**.

Bằng chứng:

- `backend/logs/isolated-build/20261104-165808-098/verification-summary.json`.
- JUnit XML/txt và JAR dưới `backend/logs/isolated-build/20261104-165808-098/target/`.
- Console Maven tại `backend/logs/build2-verify.log`; source snapshot giữ cùng thư mục isolated-build.

Đã đối chiếu 48 file source/resources/POM hiện tại với snapshot pass: khớp toàn bộ. Validator OpenAPI nội bộ PASS (47 operations, 36 paths, 70 schemas), chỉ kiểm cấu trúc YAML/refs/operationIds/parameters/responses/security; không phải full official schema validation. `git diff --check` pass. Hash V3 và hai seed mới vẫn khớp nguyên bản commit nhóm; V1/V2 không đổi. Không có lỗi regression cần sửa production trong lần chạy này.

## Kết luận trước push

**Đủ để người dùng review phần hiện có và diff bổ sung; chưa đủ nghiệm thu hoặc trình bày là BUILD2 hoàn tất để push.** Các thiếu hụt command dispatch/ACK, config sync/schema, event persist/producer, aggregation và policy House phải được chốt/hoàn thiện trước kết luận đầy đủ. Câu hỏi và đề xuất cụ thể cho leader đã có trong [báo cáo V3](build2-v3-integration.md#câu-hỏi-ngắn-gửi-leader--đề-xuất-chưa-duyệt); trạng thái chưa được tự chuyển sang “đã duyệt”.

Không commit/push/merge, không thay đổi dữ liệu DB đang dùng. Fixture mới chỉ được thêm vào DB test riêng; seed/reference và ID có sẵn giữ nguyên.
