# Chạy benchmark B2-33
Từ gốc repo trong PowerShell, sau khi cài B2-31 và tạo payload B2-32:
    
    .\ai\.venv\Scripts\python.exe .\ai\tools\b233.py --cpu "Intel Core i5-12450H" --runs 3

Mặc định dùng ai/configs/b231-sample.json, ai/samples/sample.mp4 và ai/logs/count-log-payload.json. Chọn đường dẫn khác bằng --config, --video, --payload. Nếu đổi checkpoint, ghi rõ cấu hình khác khi so sánh. Nếu có số đo nhiệt độ ngoài, truyền --temperature kèm giá trị và cách đo. Để trống khi không đo được. Dùng cùng chế độ nguồn cho các lần đo.

Kết quả chính nằm tại docs/testing/ai/fps-bandwidth-results.md (thay bản tổng hợp cũ khi chạy thành công). Mỗi phiên còn có ai/outputs/b233/<thời điểm>/report.md và log riêng. Công cụ đọc khóa để lấy độ dài, chỉ gửi khóa giả đến HTTP server cục bộ; không gọi backend, không chạy lại train hoặc thay payload gốc. Không cộng lần gửi kiểm tra --twice vào lưu lượng vận hành thông thường.

Kiểm tra trước khi bàn giao: bản sample trong checkout có 980 frame, 1280×720, 30 FPS, 11.481.940 byte; ngoại suy 1.265.356,65 KB/giờ với cùng bitrate. Với payload demo id=13 đã mô tả ở B2-32 (ID do server cấp không nằm trong POST), kiểm thử loopback tại máy chuẩn bị cho body 233 byte, HTTP upload 597 byte với khóa giả 32 byte. Header của máy khác có thể khác. Đây là số tham khảo; dùng kết quả Windows trong báo cáo.
