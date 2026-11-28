# Model card — combined_4class_v1

Mô hình phát hiện YOLOv8n fine-tuned để nhận diện swiftlet, snake, rat và owl. Bộ đếm chỉ nhận track swiftlet. PREDATOR giữ tên loài trong Event và ảnh bằng chứng.

| Artifact | Nguồn và giá trị |
|---|---|
| Weights | ai (2).zip → ai/animal_training/combined_4class_v1/weights/best.pt |
| SHA-256 best.pt | ac28931af7a4bde3c8e28b3157810801ca2a79a8fdff4757a35d37fb1d47fc2d |
| SHA-256 last.pt | 28c02f0194f21efd5e4c5563ed37ecbb051e2eed4211f6034bb009b898008b9f |
| Config train | args.yaml ghi model yolov8n.pt, 100 epoch cấu hình, patience 20, batch 4, imgsz 640, seed 0, optimizer auto |
| Log train | results.csv có các dòng tới epoch 56. Số epoch cấu hình và số epoch ghi nhận khác nhau. Phiên bản thư viện huấn luyện và lệnh gốc chưa được xác minh. |
| Môi trường kiểm tra | Ultralytics 8.4.215, PyTorch 2.15.0+cpu, OpenCV 5.0.0 |
| Cấu hình runtime | Threshold đề xuất 0,30 theo loài, 3 frame liên tiếp cùng track/loài và cooldown 60 giây trên một tiến trình camera. Ảnh JPEG tối đa 307.200 byte. |

Giải nén riêng weights và video cần chạy từ gói P2. Ví dụ từ thư mục ai, dùng weights đã giải nén và clip test. Điền device_id số của camera Backend vào bản sao config trước khi bật gửi HTTP.

```powershell
..\.venv\Scripts\python.exe -m src.main --config configs/build3-cam.example.json --model "D:\path\best.pt" --video "D:\path\owl-test.mp4" --no-send --max-frames 80
```

Khi bật HTTP, cấu hình backend_base_url là origin Backend, giữ backend_api_url cho CountLog, và đặt AI_SERVICE_KEY qua môi trường. Snapshot và Event được giữ trong SQLite theo camera cho tới khi Backend xác nhận. Một tiến trình delivery cho mỗi outbox. Các lỗi 4xx vĩnh viễn giữ nguyên bản ghi để kiểm tra. Heartbeat dùng camera_id và source_ok theo Backend hiện tại. Heartbeat có source_ok=false ghi nhận nguồn ảnh không sẵn sàng. CAMERA_OFFLINE của Backend dựa trên mất heartbeat.

[Config BUILD 3](../../ai/configs/build3-cam.example.json) · [Tài liệu dataset](dataset.md) · [Kết quả thực nghiệm](eval-v2.md)

## Follow-up 2026-11-28

The checkpoint identity stayed unchanged. Raw rat clip detection and the full runtime experiment are distinguished in the [follow-up report](build3-ai-fix-report.md). Model capacity for the rat class is insufficiently validated in dim, crowded video. The 87-image archive score may be optimistic because files with shared source-video names occur in multiple splits. Data-export README declarations are documented in [dataset.md](dataset.md); combined artifact and weights redistribution remain unverified. Re-train only after split-by-source and obtain a separately annotated independent test set.
