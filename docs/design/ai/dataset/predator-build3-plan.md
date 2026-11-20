# B2-34 | Dữ liệu mẫu và kế hoạch nhận diện động vật cho Build 3
Liên quan FR-AI-005 và FR-AI-006. Build 2 mới chuẩn bị dữ liệu. Không chạy bộ phát hiện predator, không phát Alert/Notification và không ghi sự kiện thử vào backend. Clip minh họa không chứng minh một vụ xâm nhập nhà yến.

## Inventory
| ID | Lớp | Tác phẩm / tác giả | Quyền sử dụng | Hiện trạng |
|---|---|---|---|---|
| owl01 | owl | [Barn Owl (Tyto alba)](https://commons.wikimedia.org/wiki/File:Barn_Owl_(Tyto_alba).webm), The Nature Box | [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/) | Kèm WebM 1280×720 khoảng 25 s và hai ảnh JPG ở giây 5, đã xem bằng mắt |
| snake01 | snake | [16-Grass-Snake-nX-a60](https://commons.wikimedia.org/wiki/File:16-Grass-Snake-nX-a60.webm), PsamatheM | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Nguồn video đã đối chiếu; chưa nhập vào gói |
| rat01 | rat | [Rattus norvegicus 2](https://commons.wikimedia.org/wiki/File:Rattus_norvegicus_2.jpg), Tocekas | Trang nguồn liệt kê nhiều phiên bản BY-SA; chọn [4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Nguồn ảnh đã đối chiếu; chưa có clip chuột trong gói |

## Kiểm tra và bàn giao Build 2
Ở gốc repo: chạy ai/.venv/Scripts/python.exe ai/tools/b234_check.py. Mở video và hai JPG trong ai/samples/predator. Lưu kết quả kiểm tra vào PR và nhờ một thành viên review. Phân phối mẫu cùng ATTRIBUTION.md. Ảnh cắt chỉ minh họa khâu lưu snapshot. Không có bounding box dự đoán, confidence, mAP hoặc thời gian phát cảnh báo để báo cáo. Không nhập các ảnh này vào dataset swiftlet của B2-30.

## Kế hoạch Build 3
1. P2 thu thêm clip camera cố định tại nhà yến có quyền sử dụng: ghi camera, phiên, điều kiện sáng/IR, loài, thời điểm xuất hiện, nguồn và quyền chia sẻ. Giữ cả clip trống và cảnh dễ nhầm (chim yến, bóng chuyển động). Một video quay cận cảnh ở Anh không đại diện CCTV nhà yến.
2. P2 gán nhãn snake/rat/owl theo từng frame; người thứ hai kiểm tra vùng bị che, đối tượng nhỏ và nhãn không chắc. Chia theo camera/phiên trước khi tách frame, giữ test độc lập.
3. P2 train mô hình riêng và đánh giá precision/recall theo lớp, số lần báo nhầm trên clip âm tính, bỏ sót và độ trễ trên camera đích. Chốt ngưỡng trên validation với nhóm; lưu kết quả test chưa từng dùng chọn ngưỡng.
4. P2/P4 thống nhất sự kiện và snapshot: camera, thời gian UTC, loại, confidence, phiên bản model, đường dẫn ảnh có kiểm soát truy cập. Kiểm thử chống phát nhiều sự kiện cho cùng lần xuất hiện.
5. P3/P5 đo từ lúc đối tượng xuất hiện đến khi cảnh báo hiển thị trên FE, đối chiếu mục tiêu 3–5 s bằng timestamp có đồng bộ. Chỉ nghiệm thu FR-AI-005/006 khi có bằng chứng tích hợp thật; snapshot thủ công của Build 2 không thay thế phép thử này.
