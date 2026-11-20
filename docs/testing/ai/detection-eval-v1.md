# B2-30 | Đánh giá nhận diện chim yến
Ngày ghi nhận: 19/11/2026. Nguồn kết quả: log huấn luyện và đánh giá trên Windows do P2 cung cấp.

**Kết luận:** Hoàn tất phép đo baseline trên tập chia theo video. KPI mAP@50 ≥ 0,85 chưa đạt: test = 0,135.

| Tập | Ảnh | Đối tượng được gán nhãn | Precision | Recall | mAP@50 | mAP@50–95 |
|---|---:|---:|---:|---:|---:|---:|
| Validation |126|897|0,603|0,634|0,625|0,241|
| Test |59|61|0,181|0,213|0,135|0,0409|

**Dữ liệu và phương pháp.** Xem docs/design/ai/dataset/b230-manifest.csv. Train: 389 frame DJI_0303; validation: 126 frame của hai clip WhatsApp ngày 13/01; test: 59 frame của một clip ngày 15/01; 17 ảnh LBS chưa rõ phiên được giữ ngoài. Chọn best.pt trên validation, đánh giá riêng trên test. Log quét test ghi 3 ảnh nền, 0 ảnh hỏng. Số đối tượng là số nhãn theo frame, không phải số chim riêng biệt hoặc lượt vào/ra.

**Cấu hình.** YOLOv8n, đầu vào 640, batch train 8, seed 0 theo lệnh thí nghiệm. Hoàn tất 30 epoch trong 0,905 giờ. Ultralytics 8.4.215, Python 3.14.6, torch 2.15.0+cpu, Intel Core i5-12450H. Thời gian test mỗi ảnh: tiền xử lý 1,3 ms; suy luận 35,1 ms; hậu xử lý 0,8 ms. Đây không phải FPS của toàn bộ pipeline đếm video.

**Bằng chứng trên máy chạy (từ gốc repo).**
- Checkpoint: runs/detect/ai/outputs/b230_runs/train/weights/best.pt
- Thư mục kết quả test: ai/outputs/b230_runs/test
- Log dự kiến khi dùng Tee-Object: docs/testing/ai/b230-test-console.txt
- Trước khi bàn giao PR, đính kèm log, commit chạy và SHA-256 của checkpoint mới. PowerShell: Get-FileHash .\runs\detect\ai\outputs\b230_runs\train\weights\best.pt -Algorithm SHA256

**Giới hạn và bước cải thiện.** Khoảng cách mAP@50 giữa validation và test là 0,490. Chưa đủ bằng chứng để quy nguyên nhân cho ánh sáng, kích thước chim hay lỗi nhãn. Train và test đều chỉ đại diện một video mỗi tập. Xem ví dụ bỏ sót/nhận nhầm, bổ sung nhiều phiên train độc lập; lựa chọn mô hình dựa trên validation. Nếu clip test hiện tại được dùng để định hướng các thay đổi, cần một clip độc lập mới để nghiệm thu lần kế tiếp.

## Run cũ — bằng chứng validation lịch sử
ai/animal_training/swiftlet_v1/args.yaml và results.csv: YOLOv8n, imgsz 640, batch 8, 30 epoch, seed 0. best.pt SHA-256: 3108e5252dd39280c0359d708cc7966575e6819f8f33a94c5a97f6a107f589ea. Đường dẫn dataset ghi trong args.yaml cũ không còn trong checkout; chưa đủ bằng chứng để tái dựng chính xác split của run cũ. Không sửa args.yaml lịch sử.

| Epoch | Precision | Recall | mAP50 | mAP50–95 |
|---|---:|---:|---:|---:|
| 29 (mAP50 cao nhất trong CSV) |0.66286|0.65259|0.68603|0.27370|
| 30 |0.66881|0.61900|0.68423|0.27915|

Đây là số validation theo epoch, không gán hàng 29 cho best.pt nếu chưa đánh giá chính checkpoint đó. Giá trị cao nhất nhỏ hơn mục tiêu mAP50 0.85.
