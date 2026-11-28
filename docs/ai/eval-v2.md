# BUILD3-02 — Kết quả kiểm tra AI P2

Ngày chạy 25/11/2026. Phạm vi B3-40 đến B3-46 được đối chiếu với tài liệu phân công đính kèm. Mã dùng hợp đồng AI đang chạy của SwiftletCare. Contract v1.2 vẫn cần nhóm xác nhận.

## B3-43 — Đo trên ảnh test

Đọc trực tiếp 87 ảnh và nhãn YOLO từ split test trong gói P2. Đã so SHA-256 ảnh với train/val và giữa các ảnh test. Không loại ảnh nào vì trùng byte. Kiểm tra này chưa chứng minh độc lập theo video, nguồn hoặc ảnh gần trùng.

Checkpoint combined_4class_v1, CPU, imgsz 640, confidence từ 0,30. Mỗi dự đoán ghép tối đa một nhãn cùng lớp trong cùng ảnh, ưu tiên confidence cao, IoU >= 0,50. Kết quả ở mức bounding box, chưa áp dụng ByteTrack, gate 3 frame hoặc cooldown.

| Loài | Nhãn | TP | FP | FN | Precision | Recall |
|---|---:|---:|---:|---:|---:|---:|
| Rắn | 17 | 12 | 9 | 5 | 57,14% | 70,59% |
| Chuột | 2 | 2 | 1 | 0 | 66,67% | 100,00% |
| Cú | 13 | 7 | 2 | 6 | 77,78% | 53,85% |

Hai nhãn chuột là mẫu rất nhỏ. Chưa đo false alerts/hour do thiếu video âm tính có ground truth theo thời gian. Ngưỡng nghiệm thu chất lượng chờ GV. [CSV thô](D:/swiftletcare-p2-build3-6909bbfdcf/metrics.csv) · [Script đánh giá](../../ai/tools/eval_build3.py)

## Smoke test video, retry và CountLog

| Hạng mục | Kết quả |
|---|---|
| B3-40 phát hiện và ảnh | 80 frame đầu mỗi clip. Rắn 1 event, cú 1 event, chuột 0 event, swiftlet-test1 0 event. Chạy tiếp clip có tên rat-test từ đầu tới frame 320 thì phát 1 event mang nhãn snake, chưa chứng minh phát đúng rat trên clip này. [Log rat mở rộng](D:/swiftletcare-p2-build3-6909bbfdcf/rat-extended.log) |
| B3-41 Backend và retry | Ảnh cú từ inference được giữ khi Backend chưa mở. Sau khi Backend chạy, ảnh được upload trước Event. Mô phỏng mất ACK sau commit rồi gửi lại với cùng event ID, DB vẫn có 1 snapshot, 1 Event và 1 PREDATOR Alert. GET ảnh trả 200 trong House và 403 ngoài House. |
| B3-42 heartbeat | Backend kiểm thử lưu được source_ok=true rồi false. Pytest kiểm tra worker thấy nguồn stale khi không có frame mới. Clip bị cắt ở giới hạn thử không gửi CountLog. Chưa thử RTSP hoặc camera thật. |
| B3-44 đếm chim | Cùng 80 frame của swiftlet-test1, cùng line và imgsz 640, model swiftlet_v1 (BUILD 2) ghi 14 Entry và 0 Exit. Model combined_v1 ghi 15 Entry và 0 Exit. Pipeline mới lọc riêng lớp swiftlet trước khi đếm. Chưa có ground truth crossing để xác định lượt chênh hoặc chứng minh sai số giảm. |

Pytest pass 19/19, gồm test cũ và sáu ca mới về gate, giải mã snapshot, HTTP payload, restart outbox, lỗi vĩnh viễn và heartbeat stale. B3-46 live video theo yêu cầu chưa triển khai.

[Log pytest](D:/swiftletcare-p2-build3-6909bbfdcf/pytest.log) · [Log Backend](D:/swiftletcare-p2-build3-6909bbfdcf/backend-smoke.log) · [Log BUILD 2](D:/swiftletcare-p2-build3-6909bbfdcf/swiftlet-baseline.log) · [Log BUILD 3](D:/swiftletcare-p2-build3-6909bbfdcf/swiftlet-test1.log)

## Current integration rerun

The same archived four-class checkpoint reproduced the 87-image box metrics and the 80-frame predator smoke results. Rat remained unverified. Same-clip count regression reproduced 14 ENTRY (Build 2) versus 15 (Build 3), both 0 EXIT. Ground truth for crossings and an independent negative-video observation window are still pending. See [raw CSV and annotated runs](../tests/evidence/build3-completion-ai-20261127/) and [completion audit](../tests/build3-completion-audit.md).

## 2026-11-28

The current 87-image run reproduced the original box metrics. The first 80 frames of the rat clip returned 0 rat boxes even at diagnostic confidence 0.10. A finite-segment crossing fix changed the same-geometry Build 2/3 profile counts to 11/0 and 12/0. Manual ground truth remains pending. See [AI fix report](build3-ai-fix-report.md).

## Split and negative-video follow-up

The renewed 87-image run reproduced the box metrics. Independent source split has not been established: the near-match audit includes neighboring frames of the same named source video across train/test (for example Swiftlet_DJI_0303_MP4) and across validation/test. This is a source-level contamination signal despite zero exact byte and decoded-pixel matches. Keep the full-87 scores as archive results only. See [split audit](../tests/evidence/build3-ai-followup-20261128/split-audit.json) and [follow-up runbook](build3-ai-fix-report.md).
