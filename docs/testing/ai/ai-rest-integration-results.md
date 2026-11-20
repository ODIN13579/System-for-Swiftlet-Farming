# B2-32 | AI gửi CountLog tổng hợp
Chạy từ gốc repo bằng PowerShell. Đầu vào: một camera đã bật, thuộc zone/house hoạt động. Demo local dùng CAMERA id 1; đừng dùng ID 3 của môi trường khác khi chưa đối chiếu. Backend phải chạy và ai/.env chứa AI_SERVICE_KEY khớp cấu hình backend. Không ghi khóa vào JSON, log hoặc repo.

## Chạy nhanh với payload đã lưu
    .\ai\.venv\Scripts\python.exe .\ai\tests\test_b232_handoff.py
    Get-Content .\ai\logs\count-log-payload.json
    .\ai\.venv\Scripts\python.exe .\ai\tools\b232.py send .\ai\logs\count-log-payload.json --twice
Chỉ chạy send nếu đã kiểm tra file đúng dữ liệu demo định gửi. Công cụ gửi thật, không cần mở Wokwi hay chạy lại YOLO. Thay đổi AI_SERVICE_KEY trong terminal cũ có thể che ai/.env; nếu hai nguồn khác nhau, xóa bản cũ bằng Remove-Item Env:AI_SERVICE_KEY -ErrorAction SilentlyContinue trong terminal AI rồi chạy lại.

## Tạo payload mới từ kết quả B2-31
Sao chép ai/configs/b232-period.example.json thành ai/configs/b232-period.local.json. Điền camera, kỳ tổng hợp và thời điểm ghi thực tế của từng clip (ISO 8601 với Z hoặc +07:00). Ví dụ một kỳ giờ từ 2026-11-18T08:00:00+07:00 đến 2026-11-18T09:00:00+07:00; không lấy giờ này làm thời gian quay thật nếu chưa biết. Theo quy ước nhóm, chọn đầu giờ hoặc đầu ngày. Chỉ dùng các clip cùng camera, không trùng hoặc chồng thời gian, nằm trọn trong kỳ; clip qua ranh giới cần phân bổ sự kiện theo thời gian ghi hình trước khi tổng hợp. Chờ hết kỳ rồi gửi. Một clip ngắn chỉ đại diện thời gian đã quan sát; đừng báo là giám sát đủ cả giờ. detectCount là tổng số ID theo clip, không phải số cá thể.
    
    $py = '.\ai\.venv\Scripts\python.exe'
    $payload = & $py .\ai\tools\b232.py prepare .\ai\configs\b232-period.local.json
    if ($LASTEXITCODE -eq 0) { & $py .\ai\tools\b232.py send $payload --twice }

prepare chỉ tạo file JSON cục bộ, giữ nguyên processedAt nếu chuẩn bị lại cùng số liệu. send dùng backend_api_url của config demo (có thể chỉ định --config); endpoint là POST /api/v1/internal/ai/count-logs. Chỉ gửi số liệu JSON, kèm X-Service-Key và Idempotency-Key. Tối đa 3 lần mỗi lần gọi client; retry timeout/lỗi kết nối/5xx, backoff mặc định 1 rồi 2 giây. 400: kiểm tra camera/kỳ/dữ liệu; 401/403: kiểm tra xác thực/quyền; 409: cùng kỳ nhưng số liệu hoặc processedAt khác, so sánh payload trước khi xử lý. Không tự sửa kỳ để tránh 409.

## Bằng chứng và giới hạn
- Kiểm thử tự động trong gói: 2 test đạt trên máy chuẩn bị. Tổng hợp hai clip, chặn khoảng chồng lấn; HTTP cục bộ giả lập 503 → 201 → 200 và bốn mã lỗi không retry. Không phải test Spring Boot thật.
- Log máy nhóm đã cung cấp trước đây: lần tạo CountLog nhận HTTP 201 id=9; phát lại HTTP 200 id=9 (deviceId=1, kỳ HOURLY 2026-11-18T01:00:00Z–02:00:00Z, 5 vào, 5 ra, 214 ID). Đây là bằng chứng lịch sử.
- Backend hiện tại dùng khóa duy nhất (device_id, aggregation_type, period_start, period_end); kiểm tra thêm số đếm và processed_at khi phát lại. Header đơn lẻ không bảo đảm idempotency. Với bản ghi mới mong đợi 201 rồi 200 cùng id; với bản ghi đã có, 200 rồi 200 cùng id.
- Xác minh lịch sử trên PostgreSQL (đổi device/kỳ cho lần thử khác):
    
    SELECT count(*) AS rows_for_period, min(id) AS stored_id FROM swiftletcare.count_logs WHERE device_id=1 AND aggregation_type='HOURLY' AND period_start='2026-11-18T01:00:00Z' AND period_end='2026-11-18T02:00:00Z';

Tiêu chí: rows_for_period=1 và stored_id trùng hai phản hồi HTTP. Lưu output HTTP và SQL mới để nghiệm thu trên máy nhóm. Không có kiểm thử trực tiếp DB Windows trong lần đóng gói này.
