# Dataset P2 — combined_v1

Kiểm kê trực tiếp từ ai (2).zip, thư mục ai/dataset/combined_v1. File data.yaml cung cấp danh sách lớp và tên split. Đường dẫn tuyệt đối của máy đóng gói được thay bằng truy xuất ZIP trong công cụ đánh giá.

| Split | Ảnh | File nhãn | Box swiftlet | Box snake | Box rat | Box owl |
|---|---:|---:|---:|---:|---:|---:|
| train | 1.020 | 1.020 | 975 | 398 | 348 | 75 |
| val | 293 | 293 | 1.181 | 13 | 30 | 28 |
| test | 87 | 87 | 248 | 17 | 2 | 13 |

Class ID 0 swiftlet, 1 snake, 2 rat, 3 owl. Số box là số dòng nhãn, một ảnh có thể có nhiều box.

Gói chưa có manifest nguồn, giấy phép theo từng ảnh hoặc quan hệ ảnh–video để kiểm tra rò rỉ giữa split. Giữ nguyên đánh giá trên artifact nhận được và kiểm tra trùng byte. Nguồn, quyền phân phối dataset/weights, split theo nguồn và ground truth crossing của clip cần được xác nhận trước bàn giao chính thức. [Kết quả đánh giá](eval-v2.md)


## Rà soát nguồn ngày 28/11/2026

| Gói thành phần | Metadata cục bộ | Giấy phép do README khai báo |
|---|---|---|
| Swiftlet | Roboflow ionest/swiftlet, export 22/02/2024 | Public Domain |
| Snake | Roboflow humams-workspace-5djzz/snake-detection-wt3ps, export 16/06/2026 | CC BY 4.0 |
| Rat | Roboflow viswajyothi-college-of-engineering-and-technology/rodent-6oqr3, export 09/04/2024 | CC BY 4.0 |
| Owl | Roboflow team-mrys5/owl-8sxev, export 15/05/2022 | CC BY 4.0 |

Nguồn các khai báo là `ai/dataset/extra_animals/*/README.dataset.txt`. Chưa truy được quan hệ giữa từng ảnh trong ZIP hợp nhất với bộ thành phần, nguồn gốc ảnh hoặc video. Công cụ không thấy file có tên license trong nhánh `combined_v1` của ZIP. README thành phần không xác nhận quyền phân phối ZIP hợp nhất, video đánh giá hoặc weights. Cần manifest, attribution và xác nhận quyền trước bàn giao. [Split audit](../tests/evidence/build3-ai-followup-20261128/split-audit.json)
