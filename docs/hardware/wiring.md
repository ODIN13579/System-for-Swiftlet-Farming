# B3-32 — Sơ đồ phần cứng mô phỏng

**NOT PHYSICALLY VERIFIED.** Bảng dưới đây đối chiếu sơ đồ Wokwi với sketch và cấu hình `esp32dev` đã build. LED mô phỏng chỉ thể hiện lệnh GPIO. Trạng thái tiếp điểm và thiết bị thực cần được đo trên bàn thử. [Sơ đồ Wokwi](../../firmware/diagram.json) · [Sketch](../../firmware/src/sketch.ino) · [PlatformIO](../../firmware/platformio.ini) · [Log đối chiếu GPIO](../../firmware/.pio/build3-final-review/gpio-and-soak-tool.log)

| Linh kiện trong Wokwi | Đầu tín hiệu | ESP32 | Vai trò |
| --- | --- | --- | --- |
| DHT22 | SDA | GPIO15 | Nhiệt độ, độ ẩm |
| Module quang trở | AO | GPIO34 | Ánh sáng dạng ADC |
| Biến trở | SIG | GPIO35 | Giả lập đầu vào CO₂ dạng ADC |
| LED đỏ | A | GPIO4 | FAN |
| LED xanh dương | A | GPIO16 | MIST |
| LED vàng | A | GPIO17 | HEATER |
| LED xanh lá | A | GPIO18 | AMPLIFIER |

Trong sơ đồ, ba đầu vào dùng nguồn 3V3 và GND. Cathode của bốn LED nối GND. Bàn thử LED rời cần điện trở hạn dòng phù hợp và phải kiểm tra sơ đồ chân của đúng module được chọn. [Danh mục linh kiện](bom.md)

Firmware khởi tạo FAN ở HIGH và MIST, HEATER, AMPLIFIER ở LOW. Trong AUTO, mất dữ liệu DHT hợp lệ, dữ liệu cũ quá 15 giây hoặc chưa đủ ba lần đọc tốt dẫn tới FAN ON, HEATER và MIST OFF. Kiểm tra MANUAL riêng vì nhánh điều khiển tự động thoát sớm ở chế độ này. [Sketch](../../firmware/src/sketch.ino)

Trước khi nghiệm thu thực địa, người phụ trách phần điện cần kiểm tra mức logic và cực tính của đúng module đóng cắt, trạng thái lúc cấp nguồn/reset, mất nguồn điều khiển, báo lỗi cảm biến và liên động FAN/HEATER trên bàn thử cách ly. Thiết kế cấp điện cho tải, bảo vệ quá tải và thao tác cách ly cần người có chuyên môn phụ trách. [HSE — Working safely with electrical equipment](https://www.hse.gov.uk/electricity/withequip.htm)
