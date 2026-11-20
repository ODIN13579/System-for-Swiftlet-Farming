# B2-50 | Kiểm thử tích hợp Build 2
Nền đối chiếu: main bfc3f3756148c8ce3e6cfb77ab595ca91a2f254e. Đây là báo cáo chuẩn bị nghiệm thu; kết quả của các bài chạy độc lập không thay thế một lượt chạy toàn bộ.

| Phạm vi | Bằng chứng | Trạng thái |
|---|---|---|
| MQTT thực: AUTO từ chối SET_STATE; MANUAL bật/tắt từng FAN/MIST/HEATER/AMPLIFIER; AUTO trở lại; ID trùng và xung đột; TTL hết hạn và vượt 15 s | scripts/tests/test_b250_wire.py, Paho 2.1.0 với broker aMQTT 0.11.3 qua TCP loopback | PASS, cùng một lượt với cfg bên dưới |
| MQTT cfg → cfg_ack: phiên bản mới, trùng, xung đột, cũ và ngưỡng sai | cùng test trên | PASS |
| Spring Boot → cfg → MQTT → cfg_ack → PostgreSQL: PENDING chỉ thành APPLIED sau ACK | ConfigAckIntegrationTest mới, bật CONFIG_SYNC_ENABLED trong context test | Chờ chạy Maven |
| REST → MQTT → ACK; phân quyền; timeout offline và phục hồi; AI CountLog trùng | ControlCommandsIntegrationTest, Build2ManagementIntegrationTest, IngestionIntegrationTest và các suite nền có trong repo | Chờ chạy lại trên phiên bản này |

## Chạy trên Windows
Từ gốc repo, mở Docker Desktop; dùng JDK 21, Maven, Python 3.10+ và PowerShell. Chạy:
```powershell
.\scripts\test-b250.ps1
```
Wrapper dùng bộ Compose kiểm thử trên host PostgreSQL 55433 và MQTT 51884; không cần bật FE, Wokwi hay server 8080. Các HTTP test dùng cổng ngẫu nhiên. Không dùng tài khoản phát triển. Lần đầu cần Internet để tải dependencies. Runner giữ container và volume test sau khi xong. Chỉ ghi PASS toàn bộ khi có dòng PASS cuối, Maven không có lỗi/skip và backend/verification-summary.json có ConfigAckIntegrationTest. CI chạy cùng wrapper; kết quả chi tiết trong job log và summary artifact. Sau khi chạy hãy bổ sung commit, ngày, số test và liên kết job vào báo cáo này.

## Simulator độc lập
Sau khi cài requirements.txt của scripts/mqtt-simulator, chạy python scripts/mqtt-simulator/simulator.py --port 51884 --house 1 --zone 1 --device ESP32-B250-TEST. Ctrl+C dừng heartbeat để thử offline. Nếu muốn BE nhận diện, mã và house/zone phải khớp thiết bị đã đăng ký trong DB test. Simulator giữ cache/cấu hình trong RAM, UTC lấy từ máy host, cảm biến cố định, không mô phỏng SNTP, an toàn nhiệt, lịch âm thanh hoặc lưu cấu hình qua reboot. Test Java dùng MQTT probe; test wire chạy Python độc lập trên broker, không thay thế phép thử phần cứng.
