# B3-32 — Danh mục linh kiện

**NOT PHYSICALLY VERIFIED.** Số lượng theo sơ đồ Wokwi và nhu cầu bàn thử điện áp thấp. Giá tham khảo ngày 25/11/2026 lấy từ trang bán từng linh kiện. Các mục còn thiếu mã hàng để ngỏ giá. [Sơ đồ và chân GPIO](wiring.md)

| Linh kiện | Số lượng | Ghi chú | Giá tham khảo |
| --- | ---: | --- | --- |
| ESP32 DevKit | 1 | Wokwi `board-esp32-devkit-c-v4`, firmware build với `esp32dev`. Đối chiếu pinout của đúng board trước khi mua. | Chưa xác minh |
| DHT22 | 1 | Kiểm tra dạng cảm biến rời hoặc module và linh kiện kèm theo sau khi chọn mã hàng. | Chưa xác minh |
| Module quang trở có AO | 1 | Trước khi thử, xác nhận điện áp đầu ra tương thích với ESP32. | 18.000 ₫ ở mức 1–4 chiếc. [Linh Kiện Việt](https://linhkienviet.vn/module-quang-tro) |
| Biến trở 10 kΩ | 1 | Đầu vào giả lập CO₂. Giá minh họa cho mã 3386P-1. | 5.000 ₫. [Linh Kiện Việt](https://linhkienviet.vn/bie-n-tro) |
| LED đỏ, xanh dương, vàng, xanh lá | 4 | Một LED cho mỗi đầu ra mô phỏng. | Chưa xác minh |
| Điện trở hạn dòng LED | 4 | Bổ sung cho bàn thử LED rời, chọn theo mã LED và mức điện áp thực tế. | Chưa xác minh |
| Breadboard, dây tín hiệu, cáp USB | 1 bộ | Phụ kiện cho bàn thử. | Chưa xác minh |
| Cảm biến CO₂ thực và phần cứng đóng cắt tải | Chờ chọn | Cần danh sách mã hàng và thông số thiết bị thực để nghiệm thu. | Chưa xác minh |

Chưa đủ dữ liệu để lập tổng dự toán. Phần nghiệm thu hiện có gồm đối chiếu GPIO và build firmware. [Log đối chiếu GPIO](../../firmware/.pio/build3-final-review/gpio-and-soak-tool.log) · [Log PlatformIO](../../firmware/.pio/build3-final-review/firmware-build.log)
