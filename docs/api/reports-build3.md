# B3-20 — API thống kê

Các route GET dưới `/api/v1/reports` dùng Bearer JWT và quyền READ của House chứa `zoneId`. `houseId` là bộ lọc tùy chọn. `from` và `to` là thời điểm có offset, khoảng nửa mở [from,to). Bucket JSON và CSV dùng UTC+07.

| Route | Tham số bổ sung | Kết quả |
| --- | --- | --- |
| `/environment`, `/environment.csv` | `granularity=HOURLY` hoặc `DAILY` mặc định, `sensor` tùy chọn `TEMPERATURE`, `HUMIDITY`, `CO2`, `LIGHT` | Bucket có mẫu đo, AVG/MIN/MAX và số mẫu. Giá trị null không tham gia AVG. |
| `/bird-count`, `/bird-count.csv` | `granularity=HOURLY`, `DAILY` mặc định, hoặc `WEEKLY` | Entry/Exit/Detect theo Zone. Tuần bắt đầu thứ Hai. JSON có tổng của các point báo cáo. |

CountLog chỉ tính cửa sổ nằm trọn trong khoảng lọc và đúng biên lịch. HOURLY dùng nguồn HOURLY. DAILY/WEEKLY dùng DAILY hợp lệ nếu có theo từng camera/ngày, nếu thiếu thì dùng HOURLY của camera/ngày đó. Không gộp hai nguồn của cùng camera/ngày. Khoảng thiếu không sinh point. Quy tắc ưu tiên DAILY dùng cho báo cáo và cần P4/P5 review khi hai nguồn khác nhau.

CSV dùng cùng query, header có đơn vị và ô trống khi thiếu phép đo. Không có dữ liệu thì CSV chỉ có header. GET `/devices/{id}/telemetry` giữ phân trang theo `page`/`size` và lọc khoảng ngày. Màn hình Reports React vẫn dùng fixture UI, tích hợp B3-53 còn mở.

[SQL đối chứng](../tests/build3-statistics-oracle.sql)
