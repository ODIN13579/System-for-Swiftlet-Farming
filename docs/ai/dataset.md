# Dataset P2 — combined_v1

Kiểm kê trực tiếp từ ai (2).zip, thư mục ai/dataset/combined_v1. File data.yaml cung cấp danh sách lớp và tên split. Đường dẫn tuyệt đối của máy đóng gói được thay bằng truy xuất ZIP trong công cụ đánh giá.

| Split | Ảnh | File nhãn | Box swiftlet | Box snake | Box rat | Box owl |
|---|---:|---:|---:|---:|---:|---:|
| train | 1.020 | 1.020 | 975 | 398 | 348 | 75 |
| val | 293 | 293 | 1.181 | 13 | 30 | 28 |
| test | 87 | 87 | 248 | 17 | 2 | 13 |

Class ID 0 swiftlet, 1 snake, 2 rat, 3 owl. Số box là số dòng nhãn, một ảnh có thể có nhiều box.

Gói chưa có manifest nguồn, giấy phép theo từng ảnh hoặc quan hệ ảnh–video để kiểm tra rò rỉ giữa split. Giữ nguyên đánh giá trên artifact nhận được và kiểm tra trùng byte. Nguồn, quyền phân phối dataset/weights, split theo nguồn và ground truth crossing của clip cần được xác nhận trước bàn giao chính thức. [Kết quả đánh giá](eval-v2.md)
