# B3-30 — Kịch bản Alert từ Wokwi/ESP32

**PARTIAL.** Phạm vi theo tài liệu phân công Build 3 và Jira master plan. Nghiệm thu cần Telemetry/State/Events do firmware thực trong Wokwi hoặc ESP32 phát, Alert do Backend tạo, và kiểm tra fail-safe AUTO khi DHT lỗi. Cần bàn giao video ngắn, Serial/MQTT, ảnh Alert và bảng Pass/Fail. Test Backend B3-10/B3-21 hỗ trợ đối chứng logic.

## Môi trường độc lập

Dùng Mosquitto QA qua `127.0.0.1:51885` và database mới `127.0.0.1:55435/p3_b330` với PostgreSQL container P3, backend `127.0.0.1:18086` đã trả readiness UP. Staging đổi duy nhất cổng MQTT trong sketch sang 51885, giữ client ID/topic và toàn bộ logic AUTO. Wokwi staging dùng RFC2217 4001 và GDB 3334. B3-33 dùng broker 1883 và cổng 4000/3333 ở phiên riêng. Trên cùng VS Code, chờ B3-33 kết thúc trước khi đổi tab/cửa sổ mô phỏng để tránh pause; có thể dùng máy riêng nếu bảo đảm cùng cách cô lập.

PlatformIO build `esp32dev` SUCCESS từ HEAD `7e0006433400c22df1eda3445a2008f9205ba97e`, staging `C:/Users/ACER/AppData/Local/Temp/b330-stage-6390d445506842e89b782d2cbdeec9c9/firmware`. BIN SHA-256 `259B8543E08CB863E30790A18F0232D497810A3D3793645C11720D678302F6E0`. ELF SHA-256 `7715F380627967AA2FED4D8C511B49C035EABBA2095E2D4F973C6D4CFDD0A92B`. Backend dùng JAR QA đã verify của P3-MQTT-01, SHA-256 `68d1fe53560123bb2cb317d45ed20f5299c99533d6ad89217fd504809a3927b7`.

Fixture chỉ dành cho phép đo. RH on/off 75/80%, nhiệt độ min/max 26/31 °C, telemetry 5 s, heartbeat 2 s. Đây là các giá trị mặc định firmware đối chiếu trong môi trường QA. Giữ trạng thái phê duyệt ngưỡng triển khai theo B3-31. P17 ở backend là trên 300.000 ms của cùng một điều kiện khi MANUAL. Timeout MANUAL của firmware 10 phút. Backend cảnh báo thiết bị mất heartbeat trên 9 giây; ghi mốc Alert bao gồm độ trễ scheduler. [Fixture](b3-30-fixture.sql) · [Firmware](../../firmware/src/sketch.ino) · [Alert Engine](../../backend/src/main/java/com/swiftletcare/alert/AlertEngine.java)

Kiểm tra ngày 27/11/2026 xác nhận Flyway V11, bốn actuator và cấu hình fixture 75/80/26/31. Broker QA trả CONNACK 0 và 6 SUBACK thành công. Maven `-DargLine=-Duser.timezone=UTC -Dtest=AlertEngineIntegrationTest test` chạy trên DB `p3_qa` riêng, **10 pass, 0 fail/error/skip**. Đây là regression Backend. [Log build](evidence/b3-30-preparation-20261127/platformio-build.log) · [Log Backend](evidence/b3-30-preparation-20261127/backend-regression.log)

## Bảng kịch bản

| ID | Tác động trong Wokwi | Điều kiện đạt | Kết quả E2E |
| --- | --- | --- | --- |
| RH | Baseline 28 °C, RH 84%. Giữ 28 °C, giảm RH xuống 65%. | Ba mẫu Telemetry mới liên tiếp có cùng vi phạm `LOW_HUMIDITY`, Backend tạo `ENV_THRESHOLD` WARNING sau mẫu thứ ba, giữ thêm mẫu để kiểm tra dedup. Đưa RH về 84%, quan sát `cleared_at` sau mẫu hồi phục. | NOT TESTED |
| T | Sau khi Alert RH đã clear, giữ RH 84%, nâng T lên 33 °C. | Ba mẫu liên tiếp `HIGH_TEMPERATURE`, Backend tạo hoặc mở lại `ENV_THRESHOLD`. Nếu engine dùng lại Alert, đối chiếu ID và chuyển `cleared_at` sang null giữa các snapshot. `created_at` và message của hàng cũ có thể được giữ nguyên. Đưa T về 28 °C để quan sát clear. | NOT TESTED |
| OFFLINE | Từ phiên ổn định chạy UART `TEST NETWORK OFF`, giữ tới khi có Alert rồi `TEST NETWORK ON`. | Heartbeat thực dừng, Backend tạo `DEVICE_OFFLINE` CRITICAL sau ngưỡng 9 s, UART chứng minh vòng lặp/sensor vẫn chạy; reconnect, Heartbeat và `cleared_at` xuất hiện sau phục hồi. | NOT TESTED |
| DHT | Trong AUTO ở baseline, UART `TEST DHT OFF`. Sau khi thu đủ proof, UART `TEST DHT ON`. | Có `SENSOR_FAULT` từ firmware, Telemetry null ở T/RH, Backend tạo `SENSOR_FAULT`. Cùng lượt phải thấy State AUTO, `safetyOverride=true`, FAN ON, MIST/HEATER OFF cùng Serial/LED. Khi hồi phục, firmware gửi `SENSOR_RECOVERED` sau ba lần đọc DHT tốt và backend clear Alert. | NOT TESTED |
| P17 | Ở baseline, gửi lệnh `SET_MODE MANUAL` qua broker riêng, xác nhận State MANUAL. Hạ RH xuống 65% và giữ cùng điều kiện trên 5 phút. | Backend có thể tạo `ENV_THRESHOLD` sớm. Riêng `MANUAL_OUT_OF_RANGE` chỉ được tạo sau P17 tính từ mẫu vi phạm đầu Backend quan sát khi MANUAL. Gửi AUTO và đưa RH về 84%, đối chiếu clear sau mẫu tiếp theo. | NOT TESTED |

Chạy tuần tự và ghi mốc UTC trước/sau từng thao tác. Bắt đầu kịch bản tiếp theo sau khi tín hiệu và Alert liên quan đã phục hồi. Trong DHT tránh dùng lệnh tắt FAN hoặc đổi MANUAL. Chụp UI chỉ khi đang hiển thị Alert live của backend QA và khớp đúng ID. Nếu UI đang dùng fixture, dùng ảnh truy vấn PostgreSQL có ID và timestamp làm bằng chứng Backend, ghi riêng phần ảnh UI đang thiếu.

## Thu bằng chứng

Dùng VS Code mở project staging đã xác minh. Bật Private Gateway. Mở MQTT collector trước, chờ 5 SUBACK rồi chọn **Wokwi: Start Simulator and Wait for Debugger**. Mở Serial, đợi attach rồi mới `target remote 127.0.0.1:3334` và `continue` trong GDB với ELF staging. Chạy `TEST STATUS` trong Serial Monitor, lưu BOOT và dòng CFG. Giữ cửa sổ Wokwi hiển thị. Chọn thư mục mới cho mỗi lượt.

```powershell
# Chỉ chạy khối này một lần tại repo. Ghi lại giá trị $session.
$session = 'D:/New_folder/System-for-Swiftlet-Farming/docs/tests/evidence/b3-30-run-' + (Get-Date -Format yyyyMMdd-HHmmss)
New-Item -ItemType Directory -Path $session -ErrorAction Stop | Out-Null
$topic = 'swiftletcare/1/1/ESP32-HOUSE01-ZONE01'
$py = (Resolve-Path .venv/Scripts/python.exe).Path
$project = Join-Path $env:TEMP 'b330-stage-6390d445506842e89b782d2cbdeec9c9/firmware'
code -n $project
$session
```

Sao chép đúng giá trị `$session` từ terminal tạo thư mục vào hai terminal collector.

```powershell
# Terminal MQTT; khai báo lại $py, $topic và $session nếu mở shell mới.
& $py -B scripts/capture-firmware-soak.py mqtt --driver paho --port 51885 --topic-base $topic --idle-seconds 180 --output "$session/mqtt.jsonl" --status "$session/mqtt-status.jsonl"
# Terminal Serial
& $py -B scripts/capture-firmware-soak.py serial --port 4001 --wait-seconds 120 --output "$session/serial.log" --status "$session/serial-status.jsonl"
```

Chỉ publish lệnh điều khiển vào broker QA 51885. Mở subscriber `command_ack` riêng trước khi gửi, ghi và đối chiếu đúng `command_id` và `APPLIED`. Firmware là nguồn duy nhất của Telemetry, Heartbeat, Events và State.

```powershell
# Terminal ACK riêng
& 'C:/Program Files/mosquitto/mosquitto_sub.exe' -h 127.0.0.1 -p 51885 -v -R -t "$topic/command_ack" |
  Out-File -LiteralPath "$session/command-ack.log" -Encoding utf8 -NoClobber
```

```powershell
# Terminal lệnh. Chạy khi SNTP đã sync. Sau P17 dùng 'AUTO' và tạo UUID mới.
$mode = 'MANUAL'
$command = [ordered]@{
  command_id = [guid]::NewGuid().ToString()
  actuator   = 'FAN'
  action     = 'SET_MODE'
  targetMode = $mode
  expiresAt  = [DateTime]::UtcNow.AddSeconds(10).ToString("yyyy-MM-ddTHH:mm:ss'Z'")
} | ConvertTo-Json -Compress
$command | & 'C:/Program Files/mosquitto/mosquitto_pub.exe' -h 127.0.0.1 -p 51885 -t "$topic/command" -q 1 -s
if ($LASTEXITCODE) { throw 'Command was not accepted by the QA broker.' }
$command
```

Tại các mốc baseline, phát hiện và hồi phục, chạy query chỉ đọc với tên snapshot mới. Postgres timestamps giữ UTC. Đối chiếu timestamp nhận MQTT và `received_at` trên từng `event_id` với `created_at/cleared_at` của Alert. Với OFFLINE, cần cả Heartbeat cuối trước ngắt và đầu sau kết nối lại. Ghi video ngắn các mốc chuyển giá trị, lệnh UART, chứng cứ LED an toàn và Alert thật.

```powershell
$db = 'swiftletcare-p3-qa-db-20261126'
$stamp = Get-Date -Format yyyyMMdd-HHmmss
docker exec $db psql -X --csv -U p3qa -d p3_b330 -v ON_ERROR_STOP=1 -c "select id,type,level,status,message,created_at,cleared_at from swiftletcare.alerts where zone_id=1 order by id" |
  Out-File -LiteralPath "$session/alerts-$stamp.csv" -Encoding utf8 -NoClobber
docker exec $db psql -X --csv -U p3qa -d p3_b330 -v ON_ERROR_STOP=1 -c "select t.event_id,t.timestamp,t.received_at,t.temperature,t.humidity,t.sensor_status from swiftletcare.telemetry t join swiftletcare.devices d on d.id=t.device_id where d.device_id='ESP32-HOUSE01-ZONE01' order by t.received_at,t.id" |
  Out-File -LiteralPath "$session/telemetry-$stamp.csv" -Encoding utf8 -NoClobber
```

Giữ file gốc và chỉ cập nhật kết quả từng ca sau khi có bằng chứng tương ứng.
