# BUILD3-03 — Risk Log

| Rủi ro | Tác động | Biện pháp và điều kiện đóng | Owner đề xuất |
| --- | --- | --- | --- |
| P4 quá tải hoặc mất broker khi phát ACK | Firmware retry, có thể lấp bộ đệm | Burst 50 kết nối đã kiểm tra 150/150 commit và ACK sau sửa. Hàng đợi RAM 512 mục và retry hữu hạn vẫn phụ thuộc firmware replay khi quá tải kéo dài hoặc restart; theo dõi `retry/dropped/slow_confirmation` khi triển khai. [NFR v2](nfr-build3-v2.md) | P4/P1 |
| Dữ liệu Predator thiếu đại diện | Chưa đánh giá live false positive/negative và video load | Chốt tập dữ liệu P2, đo camera thật hoặc replay fixture quyền sử dụng rõ | P2 |
| Truy cập ảnh riêng tư | Rò ảnh giữa House hoặc từ cache | Chạy suite ownership, bảo vệ service key, xác nhận chính sách retention trước cleanup | P4/P2 |
| Lệch clock hoặc timezone | Sai latency và bin CSV | JVM UTC, đo các mốc latency trên cùng host, lưu UTC và kiểm tra regression âm | P3 |
| Cross-house và token thay đổi quyền | Đọc báo cáo, ảnh hoặc WS không được cấp quyền | Regression HTTP/WS, token hết hạn và revocation trên QA | P3/P4 |
| Regression BUILD1/2 | Mất config/command/ACK khi tiến hóa schema | Chạy Maven verify thật trên DB mới và MQTT riêng, giữ từng kết quả lỗi | P3 |
| Gián đoạn MQTT/backend | Backlog Telemetry, thiếu evidence soak | Theo dõi retry, reconnect, ACK và ghi timestamp subscriber lẫn Serial | P1/P4 |
| Evidence frontend hoặc freeze thiếu | Vượt gate dù chưa có dữ liệu thật | P5 cung cấp live DOM gắn Alert ID, nhóm cung cấp biên bản freeze hoặc duyệt lại | P5/Lead |
| CountLog DAILY và HOURLY lệch | Số liệu giữa report giờ và ngày khác nhau | Review policy theo camera/ngày, tách completeness và baseline FLOCK_DROP | P4/P5 |

## Current integration risks

- P1/P4: live Wokwi command expiry, seven alert scenarios and serial evidence require an operator session.
- P2: confirm crossing labels and source independence, class-appropriate rat media and negative video duration before asserting accuracy or false alerts/hour.
- P3/P4: STORED ACK p95 was 7.45 s in the latest shared-host load despite complete delivery. Repeat under controlled load and quantify buffering under reconnect.
- Lead/GV: review P35/P36, threshold rationale, licenses, evaluation targets and freeze approval.
