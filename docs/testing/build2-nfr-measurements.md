# B2-51 · Kết quả đo NFR

Thời gian latency: ms. P95 nearest-rank. Max dùng để xét giới hạn từng mẫu. Not Tested = thiếu bằng chứng hoặc chưa chốt ngưỡng. KB/h là ước lượng HTTP upload.

## 20261120-222311-live-f3822

2026-11-20T15:23:11.279Z · Windows-11-10.0.26200-SP0 · Intel Core i5-12450H · Python 3.14.6 · Windows localhost; Docker/Wokwi (ghi rõ nếu khác) · CI: False

[Dữ liệu gốc](b251/20261120-222311-live-f3822/)

| Phép đo | Nhận/tổng | Median | P95 | Max hoặc KB/h | Mục tiêu | Kết quả |
|---|---:|---:|---:|---:|---|---|
| Telemetry MQTT observer → WS | 3/3 | 13 | 29 | 29 | 2000 | Not Tested |
| Telemetry MQTT observer → DOM | 3/3 | 62 | 72 | 72 | 2000 | Not Tested |
| Click → ACK APPLIED | 0/6 | — | — | — | 2000 | Fail |
| Heartbeat cuối → DB offline (poll) | 0/0 | — | — | — | 10000 | Not Tested |
| Heartbeat cuối → DOM Offline | 0/0 | — | — | — | 10000 | Not Tested |

## 20261120-222605-live-d3024

2026-11-20T15:26:05.009Z · Windows-11-10.0.26200-SP0 · Intel Core i5-12450H · Python 3.14.6 · Windows localhost; Docker/Wokwi (ghi rõ nếu khác) · CI: False

[Dữ liệu gốc](b251/20261120-222605-live-d3024/)

| Phép đo | Nhận/tổng | Median | P95 | Max hoặc KB/h | Mục tiêu | Kết quả |
|---|---:|---:|---:|---:|---|---|
| Telemetry MQTT observer → WS | 0/0 | — | — | — | 2000 | Not Tested |
| Telemetry MQTT observer → DOM | 0/0 | — | — | — | 2000 | Not Tested |
| Click → ACK APPLIED | 0/0 | — | — | — | 2000 | Not Tested |
| Heartbeat cuối → DB offline (poll) | 0/0 | — | — | — | 10000 | Not Tested |
| Heartbeat cuối → DOM Offline | 0/0 | — | — | — | 10000 | Not Tested |

## 20261120-222650-live-fdd3a

2026-11-20T15:26:50.384Z · Windows-11-10.0.26200-SP0 · Intel Core i5-12450H · Python 3.14.6 · Windows localhost; Docker/Wokwi (ghi rõ nếu khác) · CI: False

[Dữ liệu gốc](b251/20261120-222650-live-fdd3a/)

| Phép đo | Nhận/tổng | Median | P95 | Max hoặc KB/h | Mục tiêu | Kết quả |
|---|---:|---:|---:|---:|---|---|
| Telemetry MQTT observer → WS | 110/229 | 10.0 | 19 | 25 | 2000 | Fail |
| Telemetry MQTT observer → DOM | 110/229 | 54.0 | 63 | 90 | 2000 | Fail |
| Click → ACK APPLIED | 5/16 | 568 | 742 | 742 | 2000 | Fail |
| Heartbeat cuối → DB offline (poll) | 0/1 | — | — | — | 10000 | Fail |
| Heartbeat cuối → DOM Offline | 0/1 | — | — | — | 10000 | Fail |

## 20261120-225304-ai-e5ffa

2026-11-20T15:53:04.780Z · Windows-11-10.0.26200-SP0 · Intel Core i5-12450H · Python 3.14.6 · Windows localhost; Docker/Wokwi (ghi rõ nếu khác) · CI: False

[Dữ liệu gốc](b251/20261120-225304-ai-e5ffa/)

| Phép đo | Nhận/tổng | Median | P95 | Max hoặc KB/h | Mục tiêu | Kết quả |
|---|---:|---:|---:|---:|---|---|
| AI POST RTT (bao gồm replay) | 10/10 | 8.5 | 57.3 | 57.3 | chưa chốt | Not Tested |
| AI upload KB/h (1 POST/h, không retry) | 10 | — | — | 0.609 | KB/h, chưa chốt | Not Tested |
| AI cùng ID khi replay | 10 | — | — | — | 1 ID | Pass |

## 20261120-225306-load-68ad9

2026-11-20T15:53:06.591Z · Windows-11-10.0.26200-SP0 · Intel Core i5-12450H · Python 3.14.6 · Windows localhost; Docker/Wokwi (ghi rõ nếu khác) · CI: False

[Dữ liệu gốc](b251/20261120-225306-load-68ad9/)

| Phép đo | Nhận/tổng | Median | P95 | Max hoặc KB/h | Mục tiêu | Kết quả |
|---|---:|---:|---:|---:|---|---|
| 10 thiết bị: publish → MQTT broker | 100/100 | 5.4 | 7.7 | 9.8 | nhận đủ, không SLA tốc độ | Pass |
| 10 thiết bị: publish → Backend STORED | 0/100 | — | — | — | nhận đủ, không SLA tốc độ | Not Tested |

## 20261120-225355-load-ebe51

2026-11-20T15:53:55.152Z · Windows-11-10.0.26200-SP0 · Intel Core i5-12450H · Python 3.14.6 · Windows localhost; Docker/Wokwi (ghi rõ nếu khác) · CI: False

[Dữ liệu gốc](b251/20261120-225355-load-ebe51/)

| Phép đo | Nhận/tổng | Median | P95 | Max hoặc KB/h | Mục tiêu | Kết quả |
|---|---:|---:|---:|---:|---|---|
| 50 thiết bị: publish → MQTT broker | 500/500 | 6.5 | 15.2 | 21.0 | nhận đủ, không SLA tốc độ | Pass |
| 50 thiết bị: publish → Backend STORED | 0/500 | — | — | — | nhận đủ, không SLA tốc độ | Not Tested |

Mục chưa có lượt đo: xem từng dòng và đối chiếu đủ 10/50 thiết bị.
Phương pháp, sai số và điều kiện nghiệm thu: xem B251-runbook.md.
