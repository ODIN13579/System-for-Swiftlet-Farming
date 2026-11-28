# SwiftletCare — AI Build 3 fix và nghiệm thu

Ngày 28/11/2026 (Việt Nam). Source gốc, nhánh `integration/full-system-local`, HEAD trước và sau `1934ba641e53bd352e7145301089dc69367810ef`. Giữ thay đổi local có sẵn, clip gốc, weights, DB demo và bằng chứng cũ.

| Bug / giới hạn | Mức độ | Nguyên nhân và bằng chứng | Xử lý |
|---|---|---|---|
| COUNT-LINE-BOUNDS | P1 | Clip sample-02 rộng 568 px, config cũ đặt x=960. Đã có 15 track ID nhưng line không hợp lệ. | Giữ kiểm tra geometry và tọa độ chuẩn hóa đã bổ sung đợt trước; regression ở 568×320, 640×480, 1280×720, 1920×1080. |
| COUNT-LINE-EXTENSION | P1 | Bộ đếm trước đó đổi phía dựa trên đường thẳng vô hạn. Track đi qua bên ngoài đoạn vẽ vẫn bị tính. | Nội suy giao điểm giữa hai tâm track nằm ngoài dải hysteresis, chỉ tính khi giao nằm trên đoạn được cấu hình. Cooldown theo track giữ nguyên. |
| RAT-DETECTION | P1 | 80/80 frame giải mã được. Metadata có rat. Raw YOLO ở ngưỡng chẩn đoán 0,10 trả 99 hộp snake, 4 hộp đạt 0,30 và **0 hộp rat**. ByteTrack không trả track xác nhận trong lần chạy đó. Gate, snapshot và HTTP chưa nhận ứng viên rat. | Giữ threshold, class mapping theo metadata, ba frame cùng track/lớp và cooldown 60 s. Cần nhãn độc lập để phân biệt clip thiếu rat với lỗi nhận diện. |
| EVAL-BASELINE | P2 | Số crossing và track ID từng được dùng làm so sánh khi chưa có ground truth. | Lưu tracks/overlay, đo lại cùng clip, 80 frame, đoạn vẽ và tham số counting. CSV annotation có header, chờ người duyệt điền. |

## Audit tích hợp

- Runtime dùng YOLOv8 + ByteTrack (`bytetrack.yaml` mặc định). Sample-02 dùng `swiftlet_v1/weights/best.pt` ở imgsz 320, conf 0,25. Checkpoint predator thực tế là `D:/swiftletcare-p2-build3-6909bbfdcf/animal_training/combined_4class_v1/weights/best.pt` với [SHA-256 trong model card](model-card.md). Class swiftlet, snake, rat, owl. Detector sàng ứng viên ở 0,10 trong config Build 3, lọc chim 0,25 và predator 0,30; IoU NMS dùng mặc định của thư viện. Đánh giá ảnh dùng IoU matching 0,50, một metric khác.
- Ultralytics trả bbox theo frame gốc. Line chuẩn hóa nhân với kích thước clip gốc, hiển thị tọa độ đã tính trong log. Một crossing dùng hai quan sát tâm qua hai phía, giữ hysteresis và cooldown. Chưa ghép ID qua mất track, chưa hiệu chỉnh minimum-age/frame skipping bằng nhãn độc lập. Detect là số track ID duy nhất, có thể bị phóng đại khi ID switch.
- CountLog gửi HTTP service key, backend kiểm tra thiết bị CAMERA, period và quyền đọc theo House. Dashboard AiCounts chọn camera trong scope và đọc HOURLY cho tổng trong ngày. Alert Center truy xuất snapshot qua API xác thực. Không ghi clip thử vào demo.
- File thay đổi: `ai/src/counting.py`, `ai/src/main.py`, `ai/tests/test_counting.py`, `ai/tests/test_build3_predator.py`, tài liệu AI và evidence. Overlay thêm model version và FPS nguồn, ngoài bbox, ID, line và số đếm.

## B3-44 — Clip, ground truth và E2E

| Clip | Điều kiện | Ground truth Entry/Exit | Build 2 | Build 3 | Sai số |
|---|---|---|---|---|---|
| swiftlet-test1 | 80 frame đầu 1920×1080, 25 fps, line [960,100,960,650], imgsz 640, cùng hysteresis/cooldown và hướng | Chờ duyệt nhãn | 11/0 | 12/0 | Chưa tính |
| sample-02 | Toàn clip 568×320, 29,97 fps, line (284,40)–(284,280), swiftlet_v1 | Chờ duyệt nhãn | Không đo lại cùng calibration | 0/0, 15 track IDs | Chưa tính |

Số 14/0 và 15/0 đã báo cáo trước là kết quả trước khi giới hạn giao cắt trong đoạn. Kiểm tra metadata 1920×1080 cho thấy đường chuẩn hóa lịch sử tương ứng gần đúng cùng đoạn pixel. Đợt mới khóa tọa độ tường minh. B2/B3 vẫn là hai profile detector/tracker, vì khâu tracking có thể nhận số ứng viên khác nhau trước lọc lớp. Không kết luận cải thiện sai số.

[Bộ video overlay và logs](../tests/evidence/build3-ai-fix-20261128/) · [CSV nhãn thủ công](bird-count-ground-truth.csv). Với mỗi lượt chim nhìn rõ cắt đoạn, ghi video, frame hoặc timestamp, hướng và người duyệt. Nếu cả đoạn thực sự có 0 crossing, cần biên bản duyệt phạm vi thay cho lượt giả. Khi có nhãn, tính MAE theo từng hướng bằng trung bình |dự đoán − ground truth| trên tập clip cố định.

**CountLog QA thực:** sample-02 hoàn tất inference bằng camera `CAMERA-LOCAL` ID 2 lấy từ DB QA mới. POST trả **201**, ID 1, HOURLY 16:00–17:00Z với **0 Entry, 0 Exit, 15 Detect**. PostgreSQL có đúng dòng đó. Edge tải REST bằng phiên đăng nhập và thấy Dashboard thống kê của camera. Kỳ đo là fixture QA, chưa quy chiếu giờ thu video thật. Chưa chứng minh browser tự cập nhật sau khi đang mở hoặc hành vi theo dữ liệu nhiều kỳ trong demo. [DB](../tests/evidence/build3-ai-fix-20261128/countlog-db.txt) · [REST/Edge](../tests/evidence/build3-ai-fix-20261128/countlog-exact-dom.json)

## B3-40/43 — Đánh giá predator

| Bộ kiểm thử | Quan sát |
|---|---|
| Owl / snake, 80 frame mỗi clip | Mỗi clip một snapshot/event đúng lớp trong outbox. |
| Rat, 80 frame | Không có event. Raw box diagnostic cho thấy nguyên nhân ở đầu ra detector, cần nhãn chuột đã duyệt trước khi đổi model. |
| Swiftlet clip âm tính, 80 frame | Không có predator event trong đoạn smoke khoảng 3,2 s. Không dùng để kết luận false alerts/hour. |
| 87 test images | Snake TP/FP/FN 12/9/5, precision 57,14%, recall 70,59%. Rat 2/1/0, 66,67%/100% với chỉ hai nhãn rat. Owl 7/2/6, 77,78%/53,85%. |

[Raw detections](../tests/evidence/build3-ai-fix-20261128/rat-raw-detections.jsonl) · [CSV đánh giá](../tests/evidence/build3-ai-fix-20261128/predator-box-metrics.csv) · [Dataset và giới hạn](dataset.md). False alerts/hour **NOT TESTED**. Cần chốt thời lượng với Lead/GV. Nếu chọn cửa sổ thí điểm 60 phút, cần gần 60 phút video âm tính có nhãn, bao phủ các điều kiện hiện trường. Ngưỡng đạt **CHỜ XÁC NHẬN**.

## Snapshot retry v? quy?n truy xu?t

Clip c? th?t t?o event b448fdbe-f437-4def-a4c5-a55a55a29f19 v? JPEG c? metadata. Backend QA d?ng, k?t n?i timeout v? SQLite gi? ??ng event, ?nh, attempts=1. Kh?i t?o l?i ??i t??ng outbox sau khi Backend QA l?n, upload ?nh r?i Event th?nh c?ng. Replay tr? HTTP 200, PostgreSQL c? ??ng 1 snapshot, 1 anomaly_event, 1 PREDATOR CRITICAL. GET ?nh ??ng quy?n tr? 200 (153.501 byte), anonymous 401. Cross-house 403 v? gi?i h?n upload ???c ki?m tra trong Maven. C?c m? 400/401/403/409 thu?c l?i k?t th?c, 429/500/503 ???c retry trong ki?m th? Python. [B?ng ch?ng offline/online](../tests/evidence/build3-ai-fix-20261128/predator-retry-qa/) 

## Jira

| Jira | Trước | Sau / evidence | Status |
|---|---|---|---|
| B3-40 | Owl/snake có event, rat thiếu | Rerun bốn clip và raw diagnosis rat | PARTIAL |
| B3-41 | Có durable SQLite và bằng chứng kỳ trước | Kiểm thử 200/201, 400/401/403/409, 429/5xx, retry/replay và ảnh private | Kết quả chi tiết bên dưới |
| B3-42 | Worker source_ok, interval 10 s | Regression stale source. Live interruption/recovery và fps chưa nghiệm thu; client hiện chỉ gửi camera_id/source_ok theo API | PARTIAL |
| B3-43 | 87 ảnh có nhãn | Rerun số liệu từng lớp. Chờ negative video dài, chỉ tiêu được duyệt | PARTIAL |
| B3-44 | Line sai theo clip và thiếu ground truth | Sửa crossing segment, test nhiều độ phân giải, A/B và CountLog QA thật. Chờ nhãn và MAE | PARTIAL |
| B3-45 | Có card và dataset report | Model hash, cấu hình, split và giới hạn được ghi. Chờ license và xác minh độc lập nguồn | PARTIAL |
| B3-46 | Ngoài phạm vi Must | Chưa triển khai streaming | DEFERRED |

## Chạy Windows PowerShell

Từ `ai`, cấu hình đường đếm theo video và dùng thư mục artifact mới cho đánh giá offline:

```powershell
$out = Join-Path $env:TEMP ('sc-ai-' + [guid]::NewGuid())
& .venv/Scripts/python.exe -B -m src.main --config '<config phù hợp clip>' --video samples/sample-02.mp4 --artifact-dir $out --no-send
& .venv/Scripts/python.exe -B -m pytest -q tests
```

Để nghiệm thu ingest, dùng Backend QA, truy vấn ID camera thật bằng SELECT, xác nhận kỳ đếm của nguồn, cấu hình `backend_api_url`, đặt `AI_SERVICE_KEY` qua môi trường và bỏ `--no-send`. So HTTP 201/200, payload và DB theo camera/period, sau đó đăng nhập và đối chiếu dashboard. Không dùng kết quả từ clip cắt `--max-frames` để ingest vì pipeline chỉ gửi khi xử lý đủ clip. Demo chỉ nhận khi camera và kỳ thực đã được xác minh.

## Regression l?n n?y

- T? ai, `.venv/Scripts/python.exe -B -m pytest -q tests`: 37 passed. Bao g?m geometry, hysteresis/cooldown, gate 3 frame v? ph?n lo?i l?i HTTP.
- Maven verify tr?n PostgreSQL QA 55439 v? MQTT QA 51889: 85 passed, kh?ng l?i ho?c skip.
- Frontend npm run build th?nh c?ng; Vite c?n c?nh b?o k?ch th??c bundle. Edge fixture 16 passed, optional real test ch?y ri?ng 5 l?n v? ??t c? 5. [Log E2E](../tests/evidence/build3-ai-fix-20261128/browser-regression.txt)
- git diff --check PASS. Model v? detector thresholds gi? nguy?n. Kh?ng commit ho?c push.

## Đối chiếu bổ sung ngày 28/11/2026

### Đếm chim — B3-44 PARTIAL

Lượt **12 ENTRY** trong báo cáo trước chạy `swiftlet-test1.mp4` (1920×1080, 25 FPS, combined_4class_v1, inference imgsz 640, đoạn x=960 từ y=100 đến 650, frame 1–80). Lượt 0 chạy `sample-02.mp4` (568×320, 29,97 FPS, swiftlet_v1, imgsz 320, đoạn (284,40)–(284,280), toàn bộ 914 frame). SHA-256 hai clip khác nhau. Mỗi lệnh CLI tạo detector/tracker và bộ đếm mới. Các track ID thuộc từng lượt và không thể ghép giữa hai clip. Tọa độ đếm ở kích thước frame gốc.

Rerun chính `sample-02` với cùng bản sao config và weights cho frame 1–80 và 1–914. Cả hai có **0 crossing**, 36 bản ghi tracking đầu giống chính xác (5 track ID). Cả clip có 15 track ID. Sai khác 12/0 do đối chiếu hai đầu vào khác nhau. Chưa có bằng chứng lỗi accumulator theo thời lượng. [Đối chiếu SHA, frame và track](../tests/evidence/build3-ai-followup-20261128/count-comparison.json)

Overlay ghi frame và nhãn crossing ngay tại frame phát sinh. [Video 12 crossing](../tests/evidence/build3-ai-followup-20261128/swiftlet-crossings/annotated.mp4) · [CSV ứng viên](../tests/evidence/build3-ai-followup-20261128/crossing-candidates.csv). CSV ứng viên là đầu ra model; file `bird-count-ground-truth.csv` vẫn chờ annotation độc lập, gồm cả lượt model bỏ sót. Chưa tính MAE.

### Rat và dataset — B3-40/B3-45 PARTIAL

`rat-test.mp4` là video 1280×720, 30 FPS, 997 frame theo metadata. Kiểm tra trực quan frame 72 thấy nhiều chuột, cảnh tối, có vùng chiếu sáng mạnh và cá thể chồng lấp. Metadata checkpoint có đúng lớp `rat`. Trong phép đo 80 frame đầu ở imgsz 640, detector xuất 99 hộp snake và 0 hộp rat ở confidence từ 0,10. BBox snake có trung vị rộng 466,20 px và cao 285,91 px theo frame gốc. Bốn hộp snake đạt 0,30 ở frame 32, 67, 70, 72. Đây là kích thước dự đoán, không phải ground truth kích thước chuột. [Phân tích full clip theo frame](../tests/evidence/build3-ai-followup-20261128/rat-full-frame-detections.jsonl)

Điểm lỗi nằm ở đầu ra nhận diện lớp trong đoạn đã kiểm tra. Giữ confidence vận hành, ba frame liên tiếp, cooldown, retry và API. Trước fine-tuning, cần dữ liệu rat đã duyệt quyền dùng, có nhãn hộp chính xác, bao phủ ánh sáng yếu và che khuất. Chia tập theo video/camera, khóa test độc lập và đo cả rat recall lẫn false alerts/hour của toàn pipeline.

Split `combined_v1` có 1.020/293/87 ảnh và 348/30/2 hộp rat (train/val/test). Metadata README của các bộ thành phần và phần chưa xác minh được ghi ở [dataset.md](dataset.md). Kết quả pixel/byte không đủ để chứng minh độc lập theo nguồn.

### B3-43 — Metrics và video âm tính

Rerun 87 ảnh tái lập chính xác TP/FP/FN: snake 12/9/5, rat 2/1/0, owl 7/2/6. Không có cặp test–train hoặc test–validation trùng byte hoặc trùng ảnh pixel giải mã. Bộ lọc dHash 64 bit với khoảng cách Hamming ≤3 có 443 cặp ứng viên test–train và 348 cặp test–validation. Đây là danh sách rà soát, vẫn cần manifest nguồn/video và review ứng viên trước khi xác nhận split độc lập. [CSV metrics](../tests/evidence/build3-ai-followup-20261128/predator-box-metrics.csv) · [Split audit](../tests/evidence/build3-ai-followup-20261128/split-audit.json)

Đã thêm phép đo false alerts/hour từ video âm tính đã được xác nhận. CLI offline dùng thời gian media cho cooldown; luồng vận hành vẫn dùng đồng hồ monotonic. Công cụ chỉ chấp nhận clip hoàn chỉnh, chạy offline, bật predator và media clock, phát hiện trùng clip theo SHA-256 và từ chối công bố rate khi thiếu thời lượng đã đặt. Dùng video CFR có nhãn âm tính, nguồn phân biệt rõ và FPS xác minh được. Phép đo video đủ dài **NOT TESTED**. Lead/GV cần chốt thời lượng và ngưỡng, người duyệt cần cung cấp video âm tính độc lập và biên bản duyệt.

| Jira | Trạng thái sau lượt này | Còn thiếu |
|---|---|---|
| B3-40 | PARTIAL | Rat recall/event trên video test độc lập |
| B3-43 | PARTIAL | Rà near matches, nguồn split, video âm tính dài và ngưỡng |
| B3-44 | PARTIAL | Nhãn crossings độc lập và MAE theo hướng |
| B3-45 | PARTIAL | Manifest theo ảnh/video và xác nhận quyền với gói hợp nhất và weights |
| B3-41 | PASS kỹ thuật đã ghi nhận | Hồi quy đường API hiện hành, giữ pipeline retry nguyên vẹn |

### PowerShell chạy lại từ thư mục ai

```powershell
$out = Join-Path $env:TEMP ('swiftlet-negative-' + [guid]::NewGuid())
$weights = 'D:/swiftletcare-p2-build3-6909bbfdcf/animal_training/combined_4class_v1/weights/best.pt'
& .venv/Scripts/python.exe -B -m src.main --config configs/build3-cam.example.json --model $weights --video '<video CFR âm tính đã duyệt>' --no-send --artifact-dir $out --predator-media-clock
# 60 chỉ là ví dụ. Thay bằng thời lượng Lead/GV chốt và truyền thêm các thư mục clip đủ điều kiện nếu cần.
& .venv/Scripts/python.exe -B tools/eval_negative_video.py --reviewed-negative --minimum-minutes 60 $out
$rat = 'D:/swiftletcare-p2-build3-6909bbfdcf/samples/rat-test.mp4'
& .venv/Scripts/python.exe -B tools/audit_predator_clip.py --video $rat --weights $weights --output "$env:TEMP/rat-raw-new.jsonl"
& .venv/Scripts/python.exe -B tools/audit_dataset_splits.py 'C:/Users/ACER/Downloads/ai (2).zip' "$env:TEMP/split-audit-new.json"
& .venv/Scripts/python.exe -B -m pytest -q tests
```

Để lặp CountLog offline theo cấu hình 568×320, sao chép config camera vào đường dẫn mới, thêm `counting_line_normalized: [0.5,0.125,0.5,0.875]`, giữ nguyên weights, imgsz và hướng đếm, rồi chạy cùng artifact mới với và không có `--max-frames 80`. Mọi lần chạy inference trong lượt này dùng `--no-send`. Demo DB không nhận payload thử.

### Regression b? sung

- Python pytest 41/41, bao g?m ?o rate, ch?ng tr?ng clip v? cooldown theo th?i gian media.
- Maven integration QA 29/29, g?m AiPredatorIntegrationTest, Build2ManagementIntegrationTest v? IngestionIntegrationTest. Ch?y v?i DB QA m?i tr?n 55439 v? MQTT QA 51889.
- Edge 16 fixture ??t, test real Backend t?y ch?n ???c skip trong l??t n?y. B?ng ch?ng CountLog REST/PostgreSQL/Edge v? outbox offline/retry th?t n?m trong l??t tr??c ???c li?n k?t ? tr?n. L??t hi?n t?i kh?ng g?i clip v?o demo ho?c thay ??i API/DB.
- git diff --check ??t.

Per-frame raw inference used batches of 8, CPU, imgsz 640 and diagnostic confidence 0.10. Compared with batch-one diagnostic of the first 80 frames, preprocessing and threshold-edge scores may differ. The raw report does not emulate ByteTrack or confirm an Alert. For reruns of the 87-image evaluator, use a new output CSV and optionally append --exclude-from-split-audit with the JSON generated from the same ZIP.

### Rat full-runtime result

The full 997-frame tracked run (33.23 seconds, offline media clock) queued one snake event and zero rat events. Replaying the unchanged gate over the actual track log places the snake emission at frame 106, track 17. Later rat tracks exceeded confidence 0.30 for consecutive frames (track 103 at 898-901, track 98 at 911-913), within the 60-second cooldown started by the earlier snake event. The evidence therefore separates the absence of rat detections in the first 80 frames from the later suppression by cooldown after a misclassification. Threshold and three-frame rules remain unchanged. [Runtime summary](../tests/evidence/build3-ai-followup-20261128/rat-full-runtime/runtime-summary.json)
