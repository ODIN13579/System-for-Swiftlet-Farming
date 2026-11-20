# B2-33 | FPS và lưu lượng AI
Phiên: 20261120-121146-789931. CPU: Intel Core i5-12450H. OS: Windows-11-10.0.26200-SP0. Nhiệt độ: không đo.
Python 3.14.6, ultralytics 8.4.215, torch 2.15.0.
Video sample.mp4: 1280×720, 980 frame, 30.000 FPS nguồn. SHA-256: c28c90d37c40ad8fe9f6a8decb418cf78455fe6481e1029cce58f2405d78a2fa.
Model: best.pt; SHA-256 9256d8dd1363f3970a86f85539a6cfdc099cff50d8228c6af356ede4c2b3f775. Config: b231-sample.json; SHA-256 973ebad9312d382e49a9a550a31c3462f3433435953fd8201c3ba89880500cb9. imgsz=320, ByteTrack, CPU.

| Lần | Frame đầu ra | Giây | FPS |
|---:|---:|---:|---:|
| 1 | 980 | 62.421 | 15.700 |
| 2 | 980 | 47.013 | 20.845 |
| 3 | 980 | 42.835 | 22.878 |

FPS trung vị: 20.845. Mục tiêu ≥10 FPS: **ĐẠT**.
Mỗi lần mở tiến trình riêng; thời gian gồm import, nạp model, đọc video, YOLO, tracking, đếm, ghi video/log và thoát. Kiểm số frame ngoài khoảng đo.

CountLog camera 1, kỳ 2026-11-19T14:00:00.000000Z – 2026-11-19T15:00:00.000000Z. Giả định một POST thành công/giờ, không retry.
JSON: 233 byte. HTTP/1.1 upload (request line + headers + JSON): 609 byte = 0.609 KB/giờ.
Video: 11481940 byte / 32.667 giây → 1,265,356.65 KB/giờ nếu liên tục cùng bitrate. Video / HTTP = 2,077,761 lần.
POST thực đến máy nhận loopback tạm, dùng client dự án và khóa giả cùng độ dài. Không đo response, TCP/IP, TLS hoặc tổng lưu lượng card mạng. Video là ngoại suy từ MP4, không phải phép đo RTSP. KB=1000 byte.
