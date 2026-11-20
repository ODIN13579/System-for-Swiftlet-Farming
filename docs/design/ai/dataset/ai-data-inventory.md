# B2-30 | Dữ liệu nhận diện chim yến
Phạm vi: một lớp, nhãn nguồn 0 = walet, dùng tên swiftlet trong demo. Tách khỏi bộ rắn, chuột, cú của B2-34. Bản kiểm kê DSN cũ ở docs/ai/dataset/ai-data-inventory.md là tài liệu khảo sát, không phải danh sách train hiện tại.

Nguồn có trong repo: ai/dataset/extra_animals/Swiftlet, Roboflow ionest/swiftlet v6 (https://universe.roboflow.com/ionest/swiftlet/dataset/6). README.dataset.txt và data.yaml của bản xuất ghi Public Domain; đây là thông tin do nguồn khai báo. README.roboflow.txt ghi xuất ngày 28/07/2025, resize fit 720×480, không augmentation khi xuất. Chưa xác minh metadata phiên quay gốc. Quyền video demo ai/samples/sample.mp4 chưa xác nhận theo ai/dataset/source-register.csv; không dùng video này để chứng minh chất lượng detector.

| Nhóm theo tên nguồn | Train cũ | Val cũ | Test cũ | Split B2-30 |
|---|---:|---:|---:|---|
| DJI_0303 |257|94|38| train |
| WhatsApp 13/01 13:31:12 |9|72|9| val |
| WhatsApp 13/01 16:48:50 |22|10|4| val |
| WhatsApp 15/01 10:30:43 |6|47|6| test |
| LBS, không rõ phiên |2|13|2| giữ ngoài |
| Tổng |296|236|59| 591 ảnh |

Bản chia mới không cắt một nhóm video sang nhiều tập; hai clip WhatsApp cùng ngày được giữ cùng tập. 389 train, 126 val, 59 test; 17 ảnh LBS chờ xác minh. Danh sách từng ảnh, nhóm, số box, SHA-256 ảnh và nhãn: b230-manifest.csv. Công cụ kiểm tra cặp ảnh/nhãn, class, tọa độ YOLO và trùng byte giữa tập; không khẳng định phát hiện được mọi nguồn quay trùng dưới tên khác. Chỉ có một video train và một video test: cần thêm phiên quay độc lập để suy rộng ra nhiều nhà yến. Nhãn có sẵn từ nguồn, không tuyên bố nhóm tự gán nhãn toàn bộ.
