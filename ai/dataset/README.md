# Dataset v0 — SwiftletCare

Đây là khung dữ liệu ban đầu, **chưa phải dataset đã gán nhãn**. Các thư mục images/labels hiện để trống cho đến khi nhóm bổ sung dữ liệu có quyền sử dụng và nhãn YOLO tương ứng.

## Cấu trúc
- `images/{train,val,test}`: ảnh.
- `labels/{train,val,test}`: file nhãn YOLO `.txt` cùng tên với ảnh.
- `data.yaml`: đường dẫn tập dữ liệu và class ID.
- `source-register.csv`: sổ đăng ký nguồn dữ liệu.
- `ai-annotation-guideline.md`: quy tắc gán nhãn.
- `ai-dataset-split-plan.md`: quy tắc chia tập.

## Lưu ý
Model hiện chạy là `yolov8n.pt` pretrained trên COCO với class ID 14 (`bird`). Các class SwiftletCare trong `data.yaml` là schema mục tiêu cho dataset riêng; chưa có nghĩa model hiện tại đã nhận diện được riêng swiftlet/snake/rat/owl. Không huấn luyện hoặc báo Precision/Recall/mAP cho dataset này cho tới khi có ảnh gán nhãn và ground truth.
