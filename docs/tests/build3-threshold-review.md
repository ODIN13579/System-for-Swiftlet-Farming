# B3-31 — Đối chiếu ngưỡng nhiệt độ thấp và CO₂

| Tham số | Triển khai hiện tại | Căn cứ và quyết định |
|---|---|---|
| Nhiệt độ thấp | Firmware mặc định tempMin = 26 °C khi chưa nhận cấu hình. ZoneConfig cho phép cấu hình tempMin và tempHysteresis. | Khảo sát nhà yến Malaysia mô tả vi khí hậu thực địa và đưa khoảng tham khảo 26–35 °C. Số liệu quan sát bổ sung trung bình 28,27 °C ở một nhà mẫu. Các nguồn này không xác lập ngưỡng bật sưởi cho khu nuôi của dự án. Giữ cấu hình hiện tại và chờ Lead/GV chốt theo vị trí, hiệu chuẩn và dữ liệu thực địa. [Khảo sát DVS Malaysia](https://research.dvs.gov.my/portal/wp-content/uploads/2022/02/MJVR-V6N2-p9-22.pdf) · [Nghiên cứu nhà mẫu](https://mysitasi.mohe.gov.my/journal-website/get-meta-article?artId=b7ec594a-60a9-11ef-a699-005056a6a970&formatted=true) |
| CO₂ | Firmware Wokwi ánh xạ ADC từ biến trở thành 400–2.000 ppm. Backend hỗ trợ co2Min/co2Max có thể bỏ trống. | Khoảng ADC là dải mô phỏng, chưa hiệu chuẩn bằng cảm biến CO₂ thực. Chưa có căn cứ được duyệt cho ngưỡng sinh học của nhà yến SwiftletCare. **CHỜ XÁC NHẬN** trước khi đặt mặc định hoặc nghiệm thu B3-34. |

Source đối chiếu: `firmware/src/sketch.ino`, `ZoneConfigController.java`, `docs/design/iot/iot-hardware-spec.md`. Lượt audit chỉ cập nhật bảng căn cứ, giữ nguyên ngưỡng cấu hình và firmware.
