# SWIFTLET FARMING – AI DETECTION & COUNTING SYSTEM

## 1. Giới thiệu

Swiftlet Farming là hệ thống quản lý nhà yến tích hợp AI Computer Vision nhằm nhận diện động vật, giám sát hoạt động và đếm số lượng chim yến ra/vào nhà yến thông qua camera.

Các chức năng chính:

- Nhận diện chim yến và các loài động vật bằng YOLO.
- Nhận diện 4 lớp: Owl, Snake, Rat và Swiftlet.
- Theo dõi đối tượng qua nhiều khung hình bằng Object Tracking.
- Xác định hướng di chuyển qua Counting Line.
- Phân loại sự kiện ENTRY và EXIT.
- Gửi dữ liệu thống kê đến Backend Spring Boot.
- Lưu trữ dữ liệu bằng PostgreSQL.

## 2. Công nghệ sử dụng

| Thành phần | Công nghệ |
|---|---|
| Ngôn ngữ AI | Python |
| Object Detection | YOLOv8 |
| Object Tracking | Tracking |
| Xử lý video | OpenCV |
| Backend | Java Spring Boot |
| Database | PostgreSQL |
| Container | Docker Compose |
| Kiểm thử | Pytest |

## 3. Cấu trúc thư mục dự án

```text
System-for-Swiftlet-Farming-main/
│
├── ai/
│   ├── dataset/
│   │   └── extra_animals/
│   │       ├── owl/
│   │       │   ├── train/
│   │       │   ├── valid/
│   │       │   └── test/
│   │       ├── snake/
│   │       │   ├── train/
│   │       │   ├── valid/
│   │       │   └── test/
│   │       ├── rat/
│   │       │   ├── train/
│   │       │   ├── valid/
│   │       │   └── test/
│   │       └── swiftlet/
│   │           ├── train/
│   │           ├── valid/
│   │           └── test/
│   │
│   ├── animal_training/
│   │   ├── owl_v1/
│   │   │   └── weights/
│   │   │       ├── best.pt
│   │   │       └── last.pt
│   │   ├── snake_v1/
│   │   │   └── weights/
│   │   │       ├── best.pt
│   │   │       └── last.pt
│   │   ├── rat_v1/
│   │   │   └── weights/
│   │   │       ├── best.pt
│   │   │       └── last.pt
│   │   └── swiftlet_v1/
│   │       └── weights/
│   │           ├── best.pt
│   │           └── last.pt
│   │
│   ├── configs/
│   │   └── config.demo-cam001.json
│   ├── samples/
│   ├── outputs/
│   ├── src/
│   ├── tests/
│   └── requirements.txt
│
├── backend/
│   ├── src/
│   ├── target/
│   └── pom.xml
│
├── .env.example
├── docker-compose.yml
└── README.md
```

## 4. Dataset huấn luyện

Dataset nằm tại:

```text
ai/dataset/extra_animals/
```

Gồm 4 loài động vật:

| Loài | Thư mục |
|---|---|
| Owl | `owl/` |
| Snake | `snake/` |
| Rat | `rat/` |
| Swiftlet | `swiftlet/` |

Mỗi loài được chia thành 3 tập dữ liệu:

- `train`: Dữ liệu dùng để huấn luyện mô hình.
- `valid`: Dữ liệu dùng để đánh giá mô hình trong quá trình huấn luyện.
- `test`: Dữ liệu dùng để kiểm thử mô hình.

Thứ tự ID class được xác định theo file `data.yaml` thực tế.

## 5. Mô hình AI đã huấn luyện

Các mô hình được lưu tại:

```text
ai/animal_training/
```

| Loài | Thư mục mô hình |
|---|---|
| Owl | `owl_v1/` |
| Snake | `snake_v1/` |
| Rat | `rat_v1/` |
| Swiftlet | `swiftlet_v1/` |

Mỗi thư mục mô hình có cấu trúc:

```text
weights/
├── best.pt
└── last.pt
```

Ý nghĩa:

- `best.pt`: Trọng số mô hình có kết quả tốt nhất trong quá trình huấn luyện.
- `last.pt`: Trọng số mô hình tại epoch cuối cùng.

## 6. Cài đặt môi trường Python

Mở PowerShell tại thư mục gốc dự án:

```powershell
cd D:\System-for-Swiftlet-Farming-main
```

Kích hoạt môi trường ảo:

```powershell
.\.venv\Scripts\Activate.ps1
```

Di chuyển vào thư mục AI:

```powershell
cd ai
```

Cài đặt thư viện:

```powershell
pip install -r requirements.txt
```

## 7. Cấu hình môi trường Docker – .env.example

File `.env.example` chứa các biến môi trường dùng cho Docker và Backend.

Phần cấu hình Docker:

```env
# DOCKER
POSTGRES_DB=swiftletcare
POSTGRES_USER=swiftletcare
POSTGRES_PASSWORD=swiftletcare_local_only
POSTGRES_PORT=5432

JWT_SECRET=local-only-replace-with-random-secret-at-least-32-bytes
AI_SERVICE_KEY=local-only-replace-with-random-service-key
MQTT_PORT=1883
FRONTEND_ORIGINS=http://localhost:5173
```

## 8. Khởi động Docker và PostgreSQL

Mở PowerShell tại thư mục gốc dự án:

```powershell
cd D:\System-for-Swiftlet-Farming-main
```

Sử dụng file `.env.example` để nạp biến môi trường cho Docker Compose:

```powershell
docker compose --env-file .env.example up -d
```

Kiểm tra trạng thái container:

```powershell
docker compose ps
```

Xem log PostgreSQL:

```powershell
docker compose logs -f postgres
```

Kiểm tra PostgreSQL:

```powershell
docker exec -it system-for-swiftlet-farming-main-postgres-1 pg_isready -U swiftletcare -d swiftletcare
```

## 9. Khởi động Backend Spring Boot

Mở một cửa sổ PowerShell riêng.

Di chuyển vào thư mục Backend:

```powershell
cd D:\System-for-Swiftlet-Farming-main\backend
```

Nạp các biến môi trường cho Maven bằng PowerShell:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/swiftletcare"
$env:DB_USERNAME = "swiftletcare"
$env:DB_PASSWORD = "swiftletcare_local_only"

$env:JWT_SECRET = "local-only-replace-with-random-secret-at-least-32-bytes"
$env:AI_SERVICE_KEY = "local-only-replace-with-random-service-key"

$env:MQTT_ENABLED = "true"
# $env:MQTT_URL = "tcp://localhost:1883"
$env:MQTT_URL = "tcp://127.0.0.1:1883"
```

Kiểm tra Java:

```powershell
java -version
```

Kiểm tra Maven:

```powershell
mvn -version
```

Build Backend:

```powershell
mvn clean package -DskipTests
```

Chạy Spring Boot:

```powershell
java -jar .\target\swiftletcare-backend-0.0.1-SNAPSHOT.jar
```

Backend chạy tại:

```text
http://localhost:8080
```

API tiếp nhận dữ liệu thống kê AI:

```text
POST http://localhost:8080/api/v1/internal/ai/count-logs
```

## 10. Chạy AI Detection và Counting

Mở một cửa sổ PowerShell riêng.

Di chuyển vào thư mục AI:

```powershell
cd D:\System-for-Swiftlet-Farming-main\ai
```

Kích hoạt môi trường Python:

```powershell
..\.venv\Scripts\Activate.ps1
```

Chạy AI với video mẫu:

```powershell
python -m src.main --config configs/config.demo-cam001.json --video samples/sample.mp4 --output outputs/swiftlet_test.mp4
```

Chạy chế độ offline, không gửi dữ liệu lên Backend:

```powershell
python -m src.main --config configs/config.demo-cam001.json --video samples/sample.mp4 --output outputs/annotated.mp4 --no-send
```

Video kết quả được lưu trong thư mục `ai/outputs/`.

## 11. Quy trình nhận diện và đếm

Hệ thống xử lý theo quy trình:

1. Nhận video từ camera hoặc video mẫu.
2. YOLO phát hiện các đối tượng trong từng frame.
3. Tracking theo dõi đối tượng qua các frame.
4. Counting Line xác định đối tượng đi qua đường đếm.
5. Xác định hướng di chuyển:
   - `ENTRY`: Đối tượng đi vào nhà yến.
   - `EXIT`: Đối tượng đi ra khỏi nhà yến.
6. Tổng hợp số lượng ENTRY và EXIT.
7. Gửi dữ liệu thống kê đến Backend.
8. Backend lưu dữ liệu vào PostgreSQL.

## 12. API gửi dữ liệu AI

Endpoint:

```text
POST /api/v1/internal/ai/count-logs
```

Dữ liệu thống kê gồm các thông tin:

- `deviceId`
- `periodStart`
- `periodEnd`
- `entryCount`
- `exitCount`
- `aggregationType`

Một số mã HTTP:

| Mã | Ý nghĩa |
|---|---|
| 200 / 201 | Gửi dữ liệu thành công |
| 400 | Payload không hợp lệ |
| 401 | Chưa được xác thực |
| 404 | Không tìm thấy thiết bị |
| 409 | Dữ liệu bị trùng hoặc xung đột |

## 13. Kiểm thử AI

Tại thư mục `ai`, kích hoạt môi trường ảo rồi chạy:

```powershell
python -m pytest tests -v
```

Lệnh trên thực hiện các bài kiểm thử trong thư mục `tests`.

## 14. Quy trình khởi động toàn bộ hệ thống

### Bước 1 – Khởi động Docker

Mở PowerShell:

```powershell
cd D:\System-for-Swiftlet-Farming-main

docker compose --env-file .env.example up -d

docker compose ps
```

### Bước 2 – Khởi động Backend

Mở PowerShell riêng:

```powershell
cd D:\System-for-Swiftlet-Farming-main\backend

$env:DB_URL = "jdbc:postgresql://localhost:5432/swiftletcare"
$env:DB_USERNAME = "swiftletcare"
$env:DB_PASSWORD = "swiftletcare_local_only"

$env:JWT_SECRET = "local-only-replace-with-random-secret-at-least-32-bytes"
$env:AI_SERVICE_KEY = "local-only-replace-with-random-service-key"

$env:MQTT_ENABLED = "true"
$env:MQTT_URL = "tcp://127.0.0.1:1883"

mvn clean package -DskipTests

java -jar .\target\swiftletcare-backend-0.0.1-SNAPSHOT.jar
```

### Bước 3 – Khởi động AI

Mở PowerShell riêng:

```powershell
cd D:\System-for-Swiftlet-Farming-main\ai

..\.venv\Scripts\Activate.ps1

python -m src.main --config configs/config.demo-cam001.json --video samples/sample.mp4 --output outputs/swiftlet_test.mp4
```

## 15. Hướng phát triển

- Tối ưu độ chính xác nhận diện chim yến.
- Bổ sung nhận diện các loài động vật gây hại.
- Cải thiện thuật toán Object Tracking.
- Tối ưu thuật toán đếm ENTRY và EXIT.
- Tích hợp camera trực tiếp.
- Hoàn thiện Dashboard thống kê.
- Mở rộng quản lý nhiều nhà yến và nhiều camera.

## 16. Kết luận

Swiftlet Farming ứng dụng Computer Vision, YOLO và Object Tracking để nhận diện động vật, giám sát hoạt động và thống kê số lượng chim yến ra/vào nhà yến.

Hệ thống kết hợp AI Python, Backend Spring Boot và PostgreSQL nhằm xây dựng giải pháp giám sát nhà yến tự động, hỗ trợ quản lý và theo dõi dữ liệu tập trung.