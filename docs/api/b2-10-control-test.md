# B2-10: kiểm tra điều khiển trên Wokwi

1. Chạy Postgres, Mosquitto và backend như bình thường. Khi chạy backend bằng Maven,
   đặt thêm `$env:CONTROL_COMMANDS_ENABLED = "true"` trong PowerShell hoặc
   `set "CONTROL_COMMANDS_ENABLED=true"` trong CMD, rồi khởi động lại backend.
   Nếu dùng Compose, thêm `CONTROL_COMMANDS_ENABLED=true` vào `.env` và chạy
   `docker compose --env-file .env up -d --build backend`.
2. Khi Flyway đã chạy V5, mở pgAdmin trên **chính database của backend**. Chạy
   [provision_wokwi_actuators.sql](../../backend/src/main/resources/db/reference/provision_wokwi_actuators.sql)
   để thêm bốn actuator cho ESP32 demo. Ghi lại bốn `actuator_id` và `device_pk`
   trong kết quả; nếu không có hàng, kiểm tra mã ESP32 và database trước khi chạy tiếp.
3. So khớp `house_id`, `zone_id`, `device_id` trong kết quả với prefix topic trong
   [sketch.ino](../../firmware/src/sketch.ino). Ở thư mục `firmware` chạy `pio run`,
   rồi chạy Wokwi. Xác nhận ESP32 đã gửi heartbeat và bản tin `/state`.
4. Đăng nhập bằng tài khoản có quyền điều khiển nhà yến. Trong Postman đặt
   `Authorization: Bearer <accessToken>` và `Content-Type: application/json`.
   Kiểm tra bốn bản ghi qua `GET /api/v1/devices/{devicePk}/actuators`.
5. Gửi `POST /api/v1/actuators/{fanActuatorId}/commands` với
   `{"action":"SET_MODE","targetMode":"MANUAL"}`. HTTP 202 chứa `commandId`.
   Gọi `GET /api/v1/commands/{commandId}` để đợi ACKNOWLEDGED và kiểm tra
   GET actuator thấy mode MANUAL.
6. Gửi POST tương tự với ID của từng actuator, body
   `{"action":"SET_STATE","targetState":true}` để bật và
   `{"action":"SET_STATE","targetState":false}` để tắt. Sau mỗi lệnh, đối chiếu
   ACK, trạng thái GET actuator và LED vật lý trong Wokwi (GPIO 4/16/17/18).
   Nếu FAN OFF trả SAFETY_OVERRIDE, kiểm tra DHT22 và log safety trên Wokwi.
7. Gửi SET_MODE AUTO sau khi demo. Nếu ESP32 không trả ACK, kiểm tra API lệnh cho
   FAILED/ACK_TIMEOUT; đối chiếu trạng thái thực tế trước khi gửi tiếp.

Chạy test HTTP + PostgreSQL + Mosquitto (ESP32 được mô phỏng bằng test MQTT client):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-build2.ps1 -IsolatedBuild
```
