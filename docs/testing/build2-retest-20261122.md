# B2-52 · Đối soát và hướng dẫn retest, 22/11/2026

Phụ lục cho build2-test-plan.md, build2-test-cases.md và build2-bug-log.md. Giữ nguyên lịch sử các phiên trước. Nguồn: kết quả do người chạy cung cấp trên Windows/Wokwi; chưa chạy lại trên commit phát hành. OBS là mã theo dõi nội bộ, chưa phải ticket Jira.

| Ca | Actual và evidence | Trạng thái và việc tiếp theo |
|---|---|---|
| TC-12 / OBS-02 | 20261122-114037-live-1bb76: DB 190/190; WS và DOM cùng thiếu 67 ID. Max 99 ms và 157 ms trên 123 cặp. Sau khoảng 04:55 UTC không còn cặp; log BE tiếp tục publish với sessions=0. | Failed. P4/P5 kiểm tra lý do đóng socket và cách phục hồi phiên đăng nhập; JWT hết hạn là giả thuyết. Retest trên bản sửa ≥20 phút, ≥100 ID, đủ cặp và mỗi chặng ≤2 s. |
| TC-13 / OBS-03 | 20261122-111149-live-83247: heartbeat 04:20:58.144 UTC → DOM Offline 7.072 s, DB Offline 7.456 s. DB online trở lại, last_seen_at = 04:34:17.454303 UTC. | Phần phát hiện Offline đạt. Chốt cả ca sau khi xác nhận FE hiển thị Online trở lại. Không dùng tỷ lệ 2/1 để kết luận. P3 lưu bằng chứng UI. |
| TC-20 / OBS-01 | 20261120-222650-live-fdd3a: 16 lượt; DB 6 ACKNOWLEDGED và 10 REJECTED/INVALID_EXPIRY; bộ đo ghép được 5 APPLIED, max 742 ms. Không có số đo độ trễ cho lượt ACKNOWLEDGED thứ sáu. | Failed. P1/P4 đối chiếu expiresAt và giờ ESP32 cùng command_id; retest sau sửa với lệnh hợp lệ, ACK đúng ID, ≤2 s. |
| TC-15 / OBS-04 | B2-30: mAP50 test 0,135 so với mục tiêu 0,85. | Failed trên tập test này; P2 giữ split cố định khi đánh giá lại. |

## Chạy TC-05 tiếp theo: lệnh khi ESP32 offline

Chỉ thực hiện trên Wokwi và DB demo. P3 ghi git SHA, thời gian UTC, ID thiết bị, actuator, mode và trạng thái ban đầu. P1 đối chiếu serial/GPIO, P4 đối chiếu API, MQTT, DB. Dùng trạng thái MANUAL, FAN, một lệnh yêu cầu trạng thái khác ban đầu. Lưu log theo giờ; không đưa cookie, khóa, token lên Jira.

1. Khởi động Dashboard, MQTT, BE và Wokwi. Lúc ESP32 Online, ghi trạng thái, bật ghi Network và giám sát topic command, command_ack, state. Có thể dùng PowerShell:
   
       $root = 'swiftletcare/1/1/ESP32-HOUSE01-ZONE01'
       & "$env:ProgramFiles\mosquitto\mosquitto_sub.exe" -h 127.0.0.1 -p 1883 -R -v -t "$root/command" -t "$root/command_ack" -t "$root/state"

2. Dừng riêng Wokwi; giữ broker/BE/FE chạy. Đợi DB và UI Offline. Ghi thời điểm, sau đó thử gửi đúng một lệnh từ UI. Nếu UI khóa nút, chỉ ghi đạt phần UI. Để kiểm phần BE, dùng integration test hoặc API client được cấp quyền, với cùng endpoint/body hợp lệ đã dùng trên bản demo. Không đoán payload hoặc coi lỗi 401/400 là bằng chứng kiểm tra offline.
3. Lưu HTTP status/body, command_id nếu được cấp, MQTT và DB. Nếu có expiresAt, chỉ bật lại Wokwi sau expiresAt. Nếu BE từ chối trước khi cấp ID, ghi nhận điều đó và kết nối lại sau cửa sổ TTL của cấu hình demo.
4. Khi ESP32 reconnect, đối chiếu serial, command/ACK và LED theo ID. Firmware có thể về AUTO khi reboot: riêng một lần đổi LED do AUTO không chứng minh lệnh cũ đã chạy. Phân biệt khởi động/AUTO với thực thi SET_STATE.
5. Passed nếu có bằng chứng BE/thiết bị không thực thi lệnh offline cả sau reconnect, không hiển thị APPLIED giả. HTTP 202 đơn thuần không chứng minh đã áp dụng. Nếu lệnh offline làm đổi GPIO sau reconnect: Failed, P0, chặn demo. Nếu chỉ kiểm được nút khóa: phần BE chưa kiểm, chưa chốt cả ca.

Kết quả để điền: commit ___; UTC ___; API/ID ___; MQTT/serial ___; DB ___; UI ___; Actual ___; trạng thái ___; evidence ___; owner ___; Jira/retest ___.

## Điều kiện chốt

B2-52 có thể bàn giao phần test plan khi mọi Must có ca, có người phụ trách và sổ bug được cập nhật. Trước khi duyệt demo, cần bằng chứng cho TC-05 (lệnh offline), TC-06/07 (điều khiển sai quyền/khác nhà) và TC-01 (APPLIED sau khi thực thi). Giữ các ca chưa chạy là Not Tested, không suy ra Passed từ B2-50 hoặc B2-51.
