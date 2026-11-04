# SwiftletCare AI Service

## 1. Giới thiệu

SwiftletCare AI Service là thành phần trí tuệ nhân tạo của hệ thống quản lý và giám sát nhà yến.

Hệ thống sử dụng Computer Vision để phát hiện chim yến trong video, theo dõi chuyển động của từng đối tượng bằng ByteTrack, xác định các lượt chim bay vào (ENTRY) và bay ra (EXIT), sau đó tổng hợp dữ liệu CountLog để gửi đến Backend Spring Boot.

Phiên bản hiện tại tập trung vào chức năng phát hiện chim yến, tracking, đếm Entry/Exit và tích hợp REST API.

## 2. Công nghệ sử dụng

| Công nghệ | Chức năng |
|---|---|
| Python 3.13.6 | Ngôn ngữ phát triển |
| Ultralytics YOLO | Phát hiện đối tượng |
| PyTorch | Nền tảng chạy mô hình |
| ByteTrack | Theo dõi đối tượng qua các frame |
| OpenCV | Đọc, xử lý và xuất video |
| Requests | Gửi REST API |
| Pytest | Kiểm thử chức năng |
| Spring Boot | Backend tiếp nhận CountLog |

## 3. Cấu trúc thư mục

```text
System-for-Swiftlet-Farming-main/
│
├── ai/
│   ├── configs/
│   │   ├── config.json
│   │   └── config.demo-cam001.json
│   │
│   ├── dataset/
│   │   └── swiftlet_training/
│   │       ├── images/
│   │       │   ├── train/
│   │       │   ├── val/
│   │       │   └── test/
│   │       ├── labels/
│   │       │   ├── train/
│   │       │   ├── val/
│   │       │   └── test/
│   │       └── data.yaml
│   │
│   ├── dataset-v0/
│   │   ├── images/
│   │   ├── labels/
│   │   ├── data.yaml
│   │   ├── source-register.csv
│   │   ├── ai-data-inventory.md
│   │   ├── ai-annotation-guideline.md
│   │   └── ai-dataset-split-plan.md
│   │
│   ├── experiments/
│   │   └── experiment-report.md
│   │
│   ├── samples/
│   │   └── sample.mp4
│   │
│   ├── outputs/
│   │   └── annotated.mp4
│   │
│   ├── logs/
│   │   ├── ai-service.log
│   │   ├── count-events.jsonl
│   │   └── count-log-payload.json
│   │
│   ├── src/
│   │   ├── main.py
│   │   ├── counting.py
│   │   └── api_client.py
│   │
│   ├── tests/
│   │   ├── test_counting.py
│   │   └── test_api_client.py
│   │
│   ├── requirements.txt
│   └── README.md
│
├── runs/
│   └── detect/
│       ├── train-2/
│       │   └── weights/
│       │       ├── best.pt
│       │       └── last.pt
│       └── predict/
│
└── docs/
```

Lưu ý: `runs` được tạo tại thư mục gốc dự án khi huấn luyện hoặc chạy dự đoán. Các thư mục kết quả có thể thay đổi tên theo số lần chạy.

## 4. Chuẩn bị Dataset

### 4.1. Nguồn dữ liệu

Dataset chim yến được tải từ Roboflow và tích hợp vào dự án tại:

`ai/dataset/swiftlet_training`

Dataset được sử dụng để huấn luyện mô hình YOLO nhận diện chim yến.

Thông tin URL của project/version Roboflow và giấy phép sử dụng cần được lưu trong hồ sơ nguồn dữ liệu nếu nhóm có thông tin tương ứng.

### 4.2. Cấu trúc Dataset

```text
swiftlet_training/
├── images/
│   ├── train/
│   ├── val/
│   └── test/
│
├── labels/
│   ├── train/
│   ├── val/
│   └── test/
│
└── data.yaml
```

Số lượng ảnh trong dataset:

| Tập dữ liệu | Số ảnh |
|---|---:|
| Train | 296 |
| Validation | 236 |
| Test | 59 |
| Tổng | 591 |

### 4.3. Cấu hình data.yaml

```yaml
path: D:/System-for-Swiftlet-Farming-main/ai/dataset/swiftlet_training
train: images/train
val: images/val
test: images/test

names:
  0: swiftlet
```

Dataset huấn luyện hiện tại có một class:

- Class ID 0: swiftlet.

Các class snake, rat và owl chưa được huấn luyện trong model hiện tại.

## 5. Huấn luyện mô hình YOLO

### 5.1. Mô hình ban đầu

Sử dụng mô hình YOLOv8n pretrained làm trọng số khởi tạo:

`yolov8n.pt`

Sau đó fine-tune trên dataset chim yến đã chuẩn bị.

### 5.2. Lệnh huấn luyện

Chạy lệnh từ thư mục gốc dự án:

```powershell
python -c "from ultralytics import YOLO; model=YOLO('yolov8n.pt'); model.train(data='ai/dataset/swiftlet_training/data.yaml', epochs=30, imgsz=640, batch=8)"
```

Các tham số:

- `data`: Đường dẫn dataset.
- `epochs=30`: Huấn luyện 30 vòng.
- `imgsz=640`: Kích thước ảnh đầu vào khi huấn luyện.
- `batch=8`: Số ảnh trong mỗi batch.

### 5.3. Thư mục kết quả huấn luyện

Ultralytics tự động tạo thư mục kết quả:

```text
runs/detect/train-2/
```

Các file quan trọng:

```text
runs/detect/train-2/
├── weights/
│   ├── best.pt
│   └── last.pt
├── results.csv
├── results.png
├── confusion_matrix.png
├── PR_curve.png
├── F1_curve.png
├── P_curve.png
└── R_curve.png
```

- `best.pt`: Trọng số tốt nhất trong quá trình huấn luyện.
- `last.pt`: Trọng số tại epoch cuối cùng.
- `results.csv`: Kết quả các epoch.
- `results.png`: Biểu đồ quá trình huấn luyện.
- Các biểu đồ còn lại hỗ trợ phân tích hiệu năng mô hình.

### 5.4. Đường dẫn model hiện tại

```text
runs/detect/train-2/weights/best.pt
```

Đây là model đã được huấn luyện để sử dụng trong thử nghiệm phát hiện chim yến.

## 6. Kết quả đánh giá mô hình

Kết quả Validation đã ghi nhận:

| Chỉ số | Giá trị |
|---|---:|
| Precision | 66.9% |
| Recall | 61.9% |
| mAP50 | 68.3% |
| mAP50-95 | 27.9% |

Nhận xét:

- Mô hình đã học được khả năng phát hiện chim yến.
- Precision và Recall còn cần cải thiện.
- mAP50-95 cho thấy độ chính xác định vị bounding box vẫn còn hạn chế.
- Chưa có kết quả đánh giá độc lập trên tập Test được xác nhận trong báo cáo hiện tại.

## 7. Chạy dự đoán YOLO trên video

Có thể sử dụng model `best.pt` để dự đoán trên video mẫu.

Chạy từ thư mục gốc dự án:

```powershell
python -c "from ultralytics import YOLO; model=YOLO('runs/detect/train-2/weights/best.pt'); model.predict(source='ai/samples/sample.mp4', conf=0.25, save=True)"
```

Kết quả được lưu tự động tại:

```text
runs/detect/predict/
```

Nếu thư mục đã tồn tại, Ultralytics có thể tạo:

```text
runs/detect/predict2/
runs/detect/predict3/
```

Đây là kết quả dự đoán trực quan của YOLO, chưa phải kết quả gửi CountLog lên Backend.

## 8. AI Service – Tracking và Entry/Exit

AI Service sử dụng:

- YOLO: Phát hiện chim yến.
- ByteTrack: Duy trì tracking ID.
- EntryExitCounter: Xác định hướng di chuyển qua đường đếm.

Luồng xử lý:

```text
Camera / Video
      |
      v
YOLO Detection
      |
      v
ByteTrack Tracking
      |
      v
EntryExitCounter
      |
      v
ENTRY / EXIT Events
      |
      v
CountLog Aggregation
      |
      v
Spring Boot Backend
```

Quy tắc đếm:

- Chim đi từ ngoài vào trong: ENTRY.
- Chim đi từ trong ra ngoài: EXIT.
- Theo dõi tâm bounding box và tracking ID.
- Chỉ ghi nhận khi đối tượng vượt qua đường đếm hợp lệ.
- Hysteresis và cooldown giúp hạn chế đếm lặp khi đối tượng dao động gần đường đếm.

## 9. Cấu hình AI Service

File cấu hình sử dụng:

`ai/configs/config.demo-cam001.json`

Các thông số quan trọng:

| Tham số | Ý nghĩa |
|---|---|
| model_path | Đường dẫn model YOLO |
| class_ids | Class ID được nhận diện |
| class_name | Tên lớp |
| confidence | Ngưỡng tin cậy |
| device_id | ID thiết bị |
| camera_id | Mã camera |
| zone_id | Mã khu vực |
| counting_line | Tọa độ đường đếm |
| inside_direction | Hướng bên trong |
| hysteresis_px | Khoảng chống dao động |
| cooldown_frames | Khoảng frame chống đếm lặp |
| backend_api_url | Endpoint Backend |

Cấu hình model hiện tại cần trỏ đến:

```text
../runs/detect/train-2/weights/best.pt
```

Class ID hiện tại:

```json
"class_ids": [0],
"class_name": "swiftlet"
```

## 10. Chạy AI Service trên video

Mở PowerShell tại thư mục AI:

```powershell
cd D:\System-for-Swiftlet-Farming-main\ai
```

Kích hoạt môi trường ảo đã sử dụng:

```powershell
.\.venv\Scripts\Activate.ps1
```

Nếu môi trường ảo nằm ở thư mục gốc dự án, sử dụng đường dẫn:

```powershell
..\.venv\Scripts\Activate.ps1
```

Cài thư viện khi cần:

```powershell
pip install -r requirements.txt
```

Chạy thử offline:

```powershell
python -m src.main --video .\samples\sample.mp4 --config .\configs\config.demo-cam001.json --imgsz 320 --no-send
```

Tham số `--no-send` có nghĩa là xử lý video và tạo CountLog cục bộ nhưng không gửi dữ liệu tới Backend.

Video kết quả:

```text
ai/outputs/annotated.mp4
```

Mở video:

```powershell
Invoke-Item .\outputs\annotated.mp4
```

## 11. Kết quả chạy thử AI Service

Kết quả thử nghiệm trên video `sample.mp4`:

| Nội dung | Kết quả |
|---|---:|
| Độ phân giải | 1280 × 720 |
| Tổng frame | 980 |
| Tracking IDs | 244 |
| ENTRY | 6 |
| EXIT | 4 |
| Tổng sự kiện | 10 |

Video kết quả có bounding box, tracking ID và đường đếm.

Đây là kết quả của lần chạy AI Service hiện tại; không nhầm với kết quả dự đoán YOLO độc lập trong thư mục `runs/detect/predict`.

## 12. CountLog và tích hợp Backend

Sau khi xử lý video, AI Service tổng hợp dữ liệu CountLog.

Các trường dữ liệu:

- `deviceId`
- `periodStart`
- `periodEnd`
- `entryCount`
- `exitCount`
- `detectCount`
- `aggregationType`
- `processedAt`

Endpoint:

```text
/api/v1/internal/ai/count-logs
```

Thông tin xác thực được truyền qua biến môi trường `AI_SERVICE_KEY`.

Client REST hỗ trợ:

- Gửi POST CountLog.
- Timeout kết nối và phản hồi.
- Retry tối đa 3 lần cho lỗi kết nối, timeout và HTTP 5xx.
- Tạo Idempotency-Key ổn định cho cùng thiết bị và khoảng thời gian.

Chức năng gửi CountLog tới Spring Boot đã được người dùng xác nhận kiểm tra thành công.

## 13. Kiểm thử

Các file kiểm thử:

```text
ai/tests/
├── test_counting.py
└── test_api_client.py
```

Kiểm thử tập trung vào:

- ENTRY và EXIT.
- Không đếm khi đối tượng không vượt đường.
- Hysteresis.
- Cooldown.
- Kiểm tra Idempotency-Key.
- Retry khi gặp timeout hoặc HTTP 5xx.
- Xử lý lỗi xác thực.

Chạy kiểm thử từ thư mục `ai`:

```powershell
python -m pytest tests -v
```

## 14. Kết quả và giới hạn hiện tại

Đã thực hiện:

- Tải và chuẩn bị dataset chim yến từ Roboflow.
- Chia dữ liệu Train, Validation và Test.
- Huấn luyện YOLO 30 epochs.
- Tạo model `best.pt`.
- Đánh giá mô hình trên Validation.
- Chạy dự đoán YOLO trên video.
- Tích hợp ByteTrack và Entry/Exit Counter.
- Xuất video `annotated.mp4`.
- Tạo CountLog.
- Kiểm tra tích hợp gửi CountLog tới Backend.

Các nội dung còn cần hoàn thiện:

- Xác minh nguồn và giấy phép sử dụng dataset.
- Xác nhận việc chia tập không rò rỉ dữ liệu theo video/session.
- Đánh giá Ground Truth, MAE và ID-switch.
- Đo FPS và băng thông định lượng.
- Chuẩn bị dataset snake, rat và owl cho BUILD 3.

## 15. Định hướng phát triển

1. Cải thiện độ chính xác phát hiện chim yến.
2. Bổ sung video thử nghiệm với nhiều điều kiện ánh sáng và mật độ chim.
3. Hoàn thiện đánh giá Entry/Exit bằng Ground Truth.
4. Tối ưu tốc độ xử lý AI Service.
5. Bổ sung dataset động vật săn mồi.
6. Phát triển chức năng phát hiện và cảnh báo động vật săn mồi trong BUILD 3.

## 16. Kết luận

SwiftletCare AI Service đã xây dựng được quy trình từ chuẩn bị dataset, huấn luyện YOLO, phát hiện chim yến, tracking bằng ByteTrack, đếm ENTRY/EXIT và tích hợp gửi CountLog tới Backend Spring Boot.

Mô hình hiện tại là nền tảng cho chức năng giám sát hoạt động chim yến. Các công việc đánh giá hiệu năng, cải thiện độ chính xác và mở rộng nhận diện động vật săn mồi sẽ được thực hiện trong các giai đoạn tiếp theo.