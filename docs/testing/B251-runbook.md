# B2-51 · Hướng dẫn đo trên Windows
Làm trên checkout chạy demo hiện tại; các thao tác điều khiển chỉ thực hiện với ESP32 mô phỏng. Không chạy B2-50 song song với benchmark. Không lưu token, cookie, service key hay mật khẩu trong kết quả.

## Chuẩn bị
Từ gốc repo dùng Python 3.11 trở lên, ví dụ tạo venv riêng bằng Python có sẵn của AI:
```powershell
.\ai\.venv\Scripts\python.exe -m venv .venv-b251
$py = '.\.venv-b251\Scripts\python.exe'
& $py -m pip install -r .\scripts\b251\requirements.txt
& $py -m playwright install chromium
```
Nếu checkout không chứa ai/.venv, dùng python -m venv .venv-b251. Kiểm tra FE localhost:5173, backend localhost:8080, PostgreSQL demo cổng Windows 5433, Wokwi và backend cùng dùng broker 1883. Dùng bản FE có metric-note, device-row, device-state như main; nếu DOM đã đổi, kiểm tra selector ở scripts/b251/browser.js trước khi kết luận độ trễ. Mở một tab dashboard và giữ tab ở foreground. Bài đo mặc định house=1, zone=1, device PK=2, device code ESP32-HOUSE01-ZONE01. --help liệt kê tùy chọn.

## Telemetry, lệnh và Offline
```powershell
& $py .\scripts\measure-latency.py live --cpu 'Intel Core i5-12450H'
```
Một cửa sổ Chromium mới mở. Đăng nhập trong cửa sổ đó, chờ ESP32 Online và số đo cập nhật, rồi Enter tại terminal. Cho chạy ít nhất 100 bản (5 giây/bản: khoảng 8 phút 20 giây). Trong lúc đo, bấm MANUAL, bật/tắt từng đầu ra mô phỏng, trả lại AUTO; đợi ACK từng lệnh rồi mới bấm tiếp. Thu 10 click nếu có thể. Lệnh REJECTED, HTTP lỗi hoặc không có ACK là lần thử không thành công, không cộng vào mẫu độ trễ thành công. Để đo Offline, dừng riêng Wokwi một lần sau khi đã đủ telemetry. Giữ nguyên broker, backend, DB, FE và tab. Chờ tối thiểu 15 giây và tới khi thấy Offline; nếu không chuyển thì ghi nhận timeout. Ctrl+C tại terminal để đóng phép đo, sau đó mới bật lại Wokwi.

T0 telemetry là thời điểm subscriber trên Windows nhận bản tin không retained, xấp xỉ lúc broker nhận; không lấy timestamp ESP32 làm T0. So khớp event_id với eventId của telemetry.updated. T1 = WS message handler; T2 = MutationObserver thấy nhãn giờ mới khớp đúng timestamp mẫu trên DOM (sau khi React cập nhật, không phải phép đo pixel). Thời gian lấy trên cùng PC. Khi hai mẫu có cùng nhãn giây, selector không khớp hoặc WS tới trước MQTT observer, không ép độ trễ về 0; kiểm tra dữ liệu thô nếu thiếu cặp. Để nghiệm thu chính xác theo thời điểm broker nhận, dùng trace từ broker hoặc ghi rõ sai số quan sát.

Click → MQTT ACK chỉ ghép commandId trả bởi POST với command_id và status APPLIED; bắt thời điểm click ở trình duyệt. Deadline lệnh là cơ chế khác. Offline dùng heartbeat cuối cùng quan sát trước chuyển trạng thái làm T0; T1 = lần poll DB đầu tiên thấy online đổi t → f (mỗi ~0,5 s cộng thời gian docker exec); T2 = lần thấy hàng của đúng mã thiết bị thành Offline trên DOM. DB polling chỉ cho cận trên. Nếu đã dừng Wokwi mà cả hai đầu không quan sát được Offline, ghi rõ thử nghiệm timeout/lỗi quan sát, không diễn giải Not Tested thành đạt. Không đo nhiều chu kỳ offline trong một lượt.

## AI REST và bandwidth
```powershell
& $py .\scripts\measure-latency.py ai --samples 10 --cpu 'Intel Core i5-12450H'
```
Cần ai/src/api_client.py, ai/.env và payload thật từ B2-32; dùng --payload để đổi đường dẫn. Công cụ gửi cùng payload tới API local: có thể tạo một CountLog nếu kỳ chưa tồn tại; các lượt sau là replay của cùng kỳ. http.csv tách mã 201/200, ID và latency; không dùng 10 replay để kết luận hiệu năng tạo mới 10 kỳ. KB/h là ước lượng bytes request HTTP/1.1 khi gửi một lần thành công mỗi giờ, không tính response/TLS/TCP/retry hoặc upload snapshot. B2-33 trên máy người dùng: 0,609 KB/h, video ngoại suy 1.265.356,65 KB/h. Không dùng số này thay cho việc đo tổng bytes mạng một giờ. Mục tiêu giảm ≥90% so với truyền video liên tục chỉ kết luận sau khi so cùng phạm vi và điều kiện.

## Tải 10 và 50 ESP32
```powershell
docker compose -p swiftletcare-build2-test -f compose.build2-test.yml up -d --wait
& $py .\scripts\measure-latency.py load --devices 10 --rounds 10 --cpu 'Intel Core i5-12450H'
& $py .\scripts\measure-latency.py load --devices 50 --rounds 10 --cpu 'Intel Core i5-12450H'
```
10/50 kết nối MQTT riêng biệt, mỗi thiết bị phát một telemetry mỗi 5 giây. Chờ ACK thêm 3 giây sau lượt cuối; timeout tính là thiếu. Nếu chỉ có Compose, đây là phép thử broker ở 51884, không phải toàn hệ thống. Để đo tới backend, chạy instance backend test trỏ tới DB 55433/build2_test và broker 51884; tạo trước các ESP32 enabled B251-01..B251-50 trong cùng một nhà/khu active ở DB test, rồi chạy hai lệnh với --with-backend --house ID_NHA --zone ID_KHU. Không dùng ID thiết bị thật. Ghi rõ các tài nguyên máy và cấu hình đang dùng; chưa có số khi backend đầy tải thì ghi Not Tested.

## Báo cáo
```powershell
& $py .\scripts\measure-latency.py report
Get-Content .\docs\testing\build2-nfr-measurements.md -Encoding utf8
```
Lệnh report tổng hợp mọi lượt đo trong docs/testing/b251 và ghi lại report Markdown; không sửa tay report trước khi chạy lại. Giữ thư mục phiên (meta.json, CSV, result.json). Ghi thêm commit từ git rev-parse --short HEAD, RAM, tải nền, chu kỳ telemetry, trạng thái Wokwi và URL CI nếu có vào hồ sơ nghiệm thu. Mặc định đánh giá từng mẫu thành công so với ngưỡng, báo median, P95 nearest-rank và max. Ngưỡng telemetry UI ≤2000 ms, click → ACK ≤2000 ms, Offline ≤10000 ms. Tỷ lệ nhận đủ chỉ là tiêu chí sơ bộ của bài tải; không dùng số liệu broker thay cho SLA React. AI REST không tự đặt SLA nếu nhóm chưa duyệt.
