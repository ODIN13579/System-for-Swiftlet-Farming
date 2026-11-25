# B3-35 — So sánh điều khiển theo ngưỡng và PID

**PASS cho tái lập mô phỏng.** `3.35.zip` chứa script Python, CSV, báo cáo và hướng dẫn đặt file. Chạy script Python đã đọc mã trong thư mục thử riêng và so từng hàng với CSV đính kèm. **121/121 dòng dữ liệu trùng**, từ phút 0 đến phút 120. [Log tái lập và số liệu](../../firmware/.pio/build3-final-review/pid-run/pid-reproduction.log) · [CSV từ lượt tái lập](../../firmware/.pio/build3-final-review/pid-run/docs/iot/pid-comparison-results.csv)

Hai nhánh sử dụng cùng mô hình nhiệt xác định, bắt đầu ở 24 °C, giá trị mục tiêu thử nghiệm 28,5 °C, CO₂ hợp lệ cố định 400 ppm và liên động FAN/HEATER. Các số dưới đây tính trên chính tập CSV mô phỏng.

| Chỉ số | Điều khiển theo ngưỡng | PID |
| --- | ---: | ---: |
| Sai số tuyệt đối trung bình (°C) | 1,622 | 0,994 |
| Sai số tuyệt đối lớn nhất (°C) | 4,500 | 4,500 |
| Mức vượt mục tiêu lớn nhất (°C) | 2,531 | 0,967 |
| Tỷ lệ mẫu trong dải ±1,5 °C | 40,5% | 90,1% |
| Lần FAN đổi trạng thái | 2 | 40 |
| Lần HEATER đổi trạng thái | 2 | 2 |
| Tổng lần đổi trạng thái | 4 | 42 |

Trong mô hình này, PID giữ nhiệt độ sát mục tiêu hơn và FAN đổi trạng thái nhiều hơn. Để so sánh trên thiết bị thực cần số liệu nhiệt, CO₂ và độ bền đóng cắt của hệ thống đã chọn. Firmware AUTO tiếp tục sử dụng hysteresis hiện có. [Mã AUTO](../../firmware/src/sketch.ino) · [Đánh giá B3-31](../testing/build3-final-integration-review.md#b3-31--ngưỡng-fanheater)
