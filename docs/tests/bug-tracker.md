# BUILD3-03 — Bug Tracker

| ID | Jira | Mức độ / Ưu tiên | Owner đề xuất | Trạng thái |
| --- | --- | --- | --- | --- |
| P3-MQTT-01 | B3-22, phối hợp B3-10/P4 | Cao / P1 | P4 Backend, P1 kiểm tra retry firmware | FIXED / VERIFIED QA |
| P3-UI-02 | B3-20, B3-22, B3-53 | Cao / P1 | P5 Frontend | OPEN |
| P3-QA-03 | B3-21 | Trung bình / P2 | P3 QA | Có cách khắc phục |
| P3-FIXTURE-04 | B3-20 | Trung bình / P2 | P3 QA | Đã sửa test fixture |
| P3-QA-05 | B3-21 | Trung bình / P2 | P3 QA | Đã sửa cấu hình runner |

## P3-MQTT-01 — Thiếu STORED khi gửi dồn

- Môi trường Windows 11, Java 21, PostgreSQL 17 riêng, broker 51885, 50 ID × 3 mẫu.
- Tái hiện bằng `measure-build3.py mqtt-load`, 3 vòng gửi dồn và timeout 20 giây như [NFR v2](nfr-build3-v2.md).
- Expected 150 commit và 150 ACK khớp ID. Actual 150 commit trên 50 ID thiết bị, 20 ACK và 130 timeout. Lượt dùng 50 kết nối publisher độc lập sau Maven cho cùng kết quả. Subscriber đo thấy đủ 150 bản tin Telemetry.
- Lượt staging thêm reason code tái hiện 150 commit và chỉ 20 ACK. Có 130 ngoại lệ `32202`, khớp `REASON_CODE_MAX_INFLIGHT` của Paho 1.2.5. Gateway publish ACK QoS 1 trong callback, khi cửa sổ publish đang đầy thì việc gửi ném ngoại lệ sau commit và không có đường tự thử lại ACK trong callback.
- Bản sửa queue hữu hạn, một token chưa hoàn tất, retry/backoff có giới hạn và drain khi shutdown. Sau sửa, burst 50 kết nối ghi đúng 150 PostgreSQL commit, 150 ACK khớp ID, 0 timeout, 0 trùng, 0 ACK thiếu commit. Maven verify 85/85 pass. [Đối chiếu sau sửa](evidence/build3-p3-20261126/mqtt-ack-fix/comparison.json) · [NFR v2](nfr-build3-v2.md)
- Evidence [kết quả](evidence/build3-p3-20261126/mqtt-load/result.json), [DB count](evidence/build3-p3-20261126/mqtt-load/database-count.txt), [backend warnings](evidence/build3-p3-20261126/mqtt-load/backend-mqtt-warnings.txt), [mã lỗi chẩn đoán](evidence/build3-p3-20261126/mqtt-load-diagnostic/diagnostic.txt).

## P3-UI-02 — Alerts và Reports chưa dùng dữ liệu backend

- Mở Alerts và Reports trên branch AI-b3. Theo dõi dữ liệu khi backend tạo Alert hoặc đổi phạm vi báo cáo.
- Expected Alert có ID live từ backend và CSV khớp API. Actual Alerts khởi tạo `initialAlerts`, Reports xuất `reportRows` cục bộ. Chưa đo được DOM cho cùng ID, CSV mẫu nghiệm thu lấy trực tiếp từ API.
- Root cause là route React còn dùng fixture. P5 cần nối REST/WS và report API. Evidence [Alerts](../../frontend/src/pages/Alerts.tsx), [Reports](../../frontend/src/pages/Reports.tsx).

## P3-QA-03 — Timezone JVM làm PostgreSQL từ chối kết nối

- Windows + PostgreSQL 17 QA, chạy verify không có `-DargLine=-Duser.timezone=UTC`.
- Expected các suite khởi tạo Spring và chạy. Actual một số suite không chạy qua startup, PostgreSQL trả SQLSTATE 22023 cho `Asia/Saigon`.
- Dùng JVM timezone UTC theo quy ước runner Build 2. Giữ nguyên assertion. Evidence [Maven đầu](evidence/build3-p3-20261126/verify-initial-summary.txt).

## P3-FIXTURE-04 — Test B3-20 dùng service key fixture quá ngắn

- Lượt retry UTC đã khôi phục ConfigAck và ControlCommands. StatisticsApplicationContext vướng validation service key tối thiểu 24 ký tự.
- Dùng key fixture đúng độ dài. Expected Spring khởi tạo và các assertion HTTP/DB chạy. Evidence [retry](evidence/build3-p3-20261126/verify-retry-summary.txt).

## P3-QA-05 — Log WARN che dòng INFO mà test Sensor Event kiểm tra

- Chạy Maven verify với JVM UTC và `LOGGING_LEVEL_ROOT=WARN`. Expected appender test nhận JSON của `DeviceEvents`. Actual một assertion thất bại sau 5 giây vì appender không nhận bản ghi.
- `DeviceEvents` ghi JSON ở INFO. Runner mới đặt INFO theo runner Build 2 và chạy lại assertion nguyên vẹn. Evidence [lượt UTC/WARN](evidence/build3-p3-20261126/verify-utc-warn-summary.txt).

## Current integration triage

| ID | Severity | Expected | Actual / reproduction | Evidence | Owner | Status |
|---|---|---|---|---|---|---|
| P3-MQTT-01 | P0 | Every committed event gets STORED | Earlier lost 130/150. Re-run 50 clients x 3 completed with exact IDs, 0 timeout | [Comparison run](evidence/build3-completion-load-20261127/reconciliation.json) | P3/P4 | Regression verified for this load case |
| P2-COUNT-LINE | P1 | Wrong frame geometry is rejected rather than reporting an invalid zero | Run sample-02 (568x320) with line x=960. Old path tracked 15 IDs with 0 crossings. New validation rejects invalid coordinates. | [Audit](build3-completion-audit.md) | P2 | Fixed, acceptance accuracy still open |
| P1-EXPIRY | P0 | Valid command acknowledged and reflected in firmware | Reproduce with a correlated valid UTC command, SNTP clock and ACK on Wokwi | [Expiry report](build3-final-fix-and-e2e-report.md) | P1/P4 | PARTIAL |
| P2-RAT | P1 | Labelled rat footage emits correct species | First 80 frames of rat-test emits no predator event. Re-check labels, track continuity and threshold before tuning | [Evaluation](../ai/eval-v2.md) | P2 | OPEN |
