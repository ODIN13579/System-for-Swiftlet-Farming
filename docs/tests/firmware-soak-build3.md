# B3-33 — Soak test firmware BUILD 3

**PARTIAL.** Tài liệu đính kèm `3.33.docx` mô tả khoảng thời gian từ 17:37:29 đến 17:58:35 UTC, dài 21 phút 06 giây, với boot ID `e599169fabf1f848`. Tài liệu ghi có chuyển MANUAL → AUTO khoảng 17:46 UTC. Chưa tìm thấy serial log hoặc MQTT capture gốc của lượt này trong repo, và môi trường rà soát chưa có Wokwi CLI để thực hiện lượt 120 phút. Khoảng thời gian và số đo dưới đây là số liệu từ tài liệu đính kèm.

| Checkpoint UTC | Uptime (ms) | Free heap (byte) | Minimum heap (byte) | Buffer | Kết nối / lần thử |
| --- | ---: | ---: | ---: | --- | --- |
| 17:56 | 1.200.099 | 177.100 | 170.332 | 0/120, peak 1, drop 0 | 1 / 1 |
| 17:58:35 | 1.320.109 | 177.084 | 168.696 | 0/120, peak 1, drop 0 | 2 / 2 |

Phần diễn giải trong tài liệu gán 177.084 byte cho 17:56, khác bảng checkpoint. Giữ kết quả ở trạng thái chờ log gốc. Các mốc 18:07–19:37 được ghi là dữ liệu minh họa và được loại khỏi bằng chứng. Hai checkpoint heap cũng chưa đủ để kết luận độ ổn định bộ nhớ.

## Cách lấy bằng chứng cho 120 phút

1. Chọn bản firmware, cấu hình MQTT, House, Zone và Device riêng cho kiểm thử. Ghi phiên bản mã và mốc bắt đầu UTC. Bắt đầu lưu serial trước boot, giữ cùng clock tham chiếu cho các nguồn dữ liệu và xác định mốc vào AUTO.
2. Thu MQTT trên broker kiểm thử trong cùng cửa sổ. Với thiết bị dùng serial vật lý, dùng serial monitor PlatformIO ở 115200. Với Wokwi, lưu serial từ phiên mô phỏng có kết nối tới broker kiểm thử. [Cấu hình firmware](../../firmware/platformio.ini) · [Topic MQTT](../mqtt/topics.md)

   ```powershell
   $testMqttHost = '127.0.0.1'
   $testMqttPort = 1883
   $deviceTopic = 'swiftletcare/<house>/<zone>/<device>'
   mosquitto_sub -v -R -h $testMqttHost -p $testMqttPort -t "$deviceTopic/telemetry" -t "$deviceTopic/telemetry_ack" -t "$deviceTopic/heartbeat" -t "$deviceTopic/state" -t "$deviceTopic/events" |
     Out-File -FilePath mqtt-soak.log -Encoding utf8
   ```

3. Giữ đủ 120 phút quan sát liên tục sau mốc AUTO và checkpoint `[RESOURCE]` đầu tiên. Ghi mốc mọi lần đổi mode hay gián đoạn có chủ đích. Nếu reboot hoặc đổi mode, báo từng khoảng riêng. Lưu serial và MQTT gốc cùng ghi chú chuyển mode.
4. Chạy script tổng hợp và đối chiếu với log gốc trước khi chấm. Kiểm tra `boot_id`, reset reason, uptime, khoảng cách checkpoint, heap, số lần kết nối lại, bộ đệm, dropped events, các lỗi TX/RX và đối chiếu `event_id` của `STORED`. Heartbeat trong báo cáo dùng timestamp của thiết bị. Muốn kết luận độ trễ theo thời điểm nhận tại broker, cần thêm timestamp phía thu và đồng bộ đồng hồ. [Script kiểm tra](../../scripts/audit-firmware-soak.py)

   ```powershell
   python scripts/audit-firmware-soak.py --serial serial-soak.log --mqtt mqtt-soak.log --topic-base $deviceTopic
   ```

Lệnh trên đã qua kiểm tra với hai tệp serial và MQTT tổng hợp, bao gồm reset uptime, dung lượng buffer và đối chiếu `STORED`. Báo cáo script chỉ tổng hợp bằng chứng từng lượt, trạng thái nghiệm thu 120 phút vẫn chờ lượt chạy đủ thời gian. [Log kiểm tra script](../../firmware/.pio/build3-final-review/gpio-and-soak-tool.log)
