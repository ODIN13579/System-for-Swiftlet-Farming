# SwiftletCare Backend — BUILD1-04 MVP

## BUILD 2 trên htran — triển khai từng phần

Xem [bàn giao BUILD 2](../docs/api/build2-handoff.md) và [tích hợp V3](../docs/api/build2-v3-integration.md). Đã có House tạo nguyên tử kèm initial Zone, đọc/list/sửa/disable/re-enable; Zone/Device quản lý, CountLog GET, actuator/command GET và ZoneConfig GET/PUT với version. Global ADMIN active được truy cập toàn hệ thống. **PUT config hiện chỉ lưu DB, trả syncStatus UNKNOWN, chưa publish MQTT.** V3 giữ nguyên nhóm, không tạo migration mới. Events có structured log, chưa persist; command dispatch/config sync và thống kê hôm nay còn chờ leader chốt.

Test riêng từ repository root: `./scripts/test-build2.ps1 -IsolatedBuild -TestDatabase build2_v3_review_20261102` (JDK theo POM của checkout, DB review đã được tạo); dùng `-Offline` nếu cache đầy đủ. Nếu POM local yêu cầu Java25 nhưng máy chỉ có Java21, thêm `-Java21Compatibility`; đó là kiểm tra tương thích riêng. Kết quả trước fast-forward ngày 04/11/2026: **27/27 pass, không fail/error/skip**; [bảng sáu task và bằng chứng](../docs/api/build2-resume-review.md). DB/broker độc lập, không xóa volume. Thay đổi nâng Java25 local được tách khỏi commit BUILD2; xem [xác minh sau fast-forward](../docs/api/build2-push-verification.md).

Java 21, Maven 3.6.3+, Spring Boot 4.0.8, PostgreSQL 17, Flyway, JPA, MQTT (Paho), REST, plain WebSocket, JWT HS256. Cần Docker Desktop Linux containers. Kết quả chạy thật xem [BUILD1-04 verification](../docs/api/build104-verification.md); contract bàn giao frontend/AI: [build104-mvp.md](../docs/api/build104-mvp.md), MQTT: [topics.md](../docs/mqtt/topics.md).

## Chạy Windows / PowerShell

Từ gốc repository `System-for-Swiftlet-Farming`:

```powershell
java -version
mvn -version
docker compose version
# Chỉ copy lần đầu. Nếu đã có .env, bổ sung biến mới từ .env.example bằng tay.
if (!(Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --wait postgres mqtt
$dbConfig = ConvertFrom-StringData (Get-Content .env -Raw)
$env:DB_URL = "jdbc:postgresql://localhost:$($dbConfig.POSTGRES_PORT)/$($dbConfig.POSTGRES_DB)"
$env:DB_USERNAME = $dbConfig.POSTGRES_USER
$env:DB_PASSWORD = $dbConfig.POSTGRES_PASSWORD
$env:JWT_SECRET = $dbConfig.JWT_SECRET
$env:AI_SERVICE_KEY = $dbConfig.AI_SERVICE_KEY
$env:MQTT_URL = "tcp://localhost:$($dbConfig.MQTT_PORT)"
$env:FRONTEND_ORIGINS = 'http://localhost:5173'
# Chỉ để tạo dữ liệu mẫu local, trên DB rỗng. Bỏ hai dòng này nếu dùng DB đã có dữ liệu.
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:LOCAL_ADMIN_PASSWORD = 'replace-with-your-local-password'
mvn -f backend/pom.xml spring-boot:run
```

.env chỉ dành cho Compose, Spring không tự đọc file này. Không commit .env. Secret trong .env.example là minh họa local, thay bằng secret riêng. Nếu .env cũ chưa có MQTT_PORT, đặt 1883. JWT_SECRET >=32 byte, AI_SERVICE_KEY >=24 ký tự. Profile local chỉ seed khi bảng houses rỗng, không đổi mật khẩu/dữ liệu đã có. Tạo local@example.test (OWNER), một House/Zone, ESP32-LOCAL và CAMERA-LOCAL; tất cả cùng transaction. DB sản xuất không bật profile local. Account mới không có API CRUD trong MVP.

SERVER_PORT mặc định 8080. HEARTBEAT_TIMEOUT_MS=6000, STATUS_SCAN_MS=1000. MQTT_CLIENT_ID phải riêng cho mỗi instance; MVP một instance. MQTT_USERNAME/MQTT_PASSWORD hỗ trợ broker có auth. FRONTEND_ORIGINS là danh sách origin cách nhau dấu phẩy. Health tổng hợp/readiness phụ thuộc DB; liveness chỉ phụ thuộc ứng dụng:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
$login = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/auth/login `
  -ContentType 'application/json' `
  -Body (@{ email='local@example.test'; password=$env:LOCAL_ADMIN_PASSWORD } | ConvertTo-Json)
$headers = @{ Authorization="Bearer $($login.accessToken)" }
# Lấy zoneId/device ID thật từ DB. ID dưới đây minh họa cho DB local mới.
Invoke-RestMethod 'http://localhost:8080/api/v1/zones/1/devices' -Headers $headers
Invoke-RestMethod 'http://localhost:8080/api/v1/devices/1' -Headers $headers
Invoke-RestMethod 'http://localhost:8080/api/v1/devices/1/telemetry/latest' -Headers $headers
Invoke-RestMethod 'http://localhost:8080/api/v1/devices/1/telemetry?from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z&page=0&size=20' -Headers $headers
```

Ví dụ MQTT dùng client có sẵn trong container broker. Thay House/Zone bằng ID thật. Mở một terminal subscribe ACK trước, rồi terminal khác publish:

```powershell
docker compose exec mqtt mosquitto_sub -t 'swiftletcare/1/1/ESP32-LOCAL/telemetry_ack' -q 1 -v
# Dùng stdin để tránh lỗi escape JSON giữa PowerShell/Windows/Docker.
'{"device_id":"ESP32-LOCAL","event_id":"local-001","timestamp":"2026-10-22T00:00:00Z","temperature":29.5,"humidity":80,"co2":null,"light":12,"sensor_status":"PARTIAL_ERROR"}' |
  docker compose exec -T mqtt mosquitto_pub -t 'swiftletcare/1/1/ESP32-LOCAL/telemetry' -s -q 0
@{device_id='ESP32-LOCAL';timestamp=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json -Compress |
  docker compose exec -T mqtt mosquitto_pub -t 'swiftletcare/1/1/ESP32-LOCAL/heartbeat' -s -q 0
```

CountLog HTTP (camera PK minh họa là 2; không dùng device code):

```powershell
$count = @{deviceId=2;periodStart='2026-10-22T00:00:00Z';periodEnd='2026-10-22T01:00:00Z';entryCount=12;exitCount=3;detectCount=15;aggregationType='HOURLY';processedAt='2026-10-22T01:00:01Z'} | ConvertTo-Json
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/internal/ai/count-logs `
  -Headers @{'X-Service-Key'=$env:AI_SERVICE_KEY} -ContentType 'application/json' -Body $count
```

Ví dụ React/WebSocket, auth cookie, schema realtime và xử lý reconnect nằm trong [contract bàn giao](../docs/api/build104-mvp.md).

## Build và kiểm thử riêng

Log kiểm thử lưu tại `backend/logs/` (được Git bỏ qua); script recovery tự tạo thư mục này. Khi redirect output thủ công, tạo thư mục trước và dùng đường dẫn như `backend/logs/build104-verify.log`. Báo cáo JUnit vẫn ở `backend/target/surefire-reports/`.

```powershell
# Không chạy integration suite trên DB có dữ liệu nghiệp vụ.
docker compose -p swiftletcare-build104-e2e -f compose.build104-test.yml up -d --wait
$env:DB_URL = 'jdbc:postgresql://localhost:55432/build104_test'
$env:DB_USERNAME = 'build104'
$env:DB_PASSWORD = 'build104-test-only'
$env:DB_TEST_ENABLED = 'true'
Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
mvn -f backend/pom.xml clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build/test failed' }
Remove-Item Env:DB_TEST_ENABLED
# Sau khi nạp lại biến kết nối/local secret theo phần chạy ứng dụng:
java -jar backend/target/swiftletcare-backend-0.0.1-SNAPSHOT.jar
# Dockerfile multi-stage build riêng:
docker build -t swiftletcare-backend:local backend
# Smoke JAR với timeout mặc định, restart broker và dừng/khởi động DB test:
# Cần Python 3.10+; script chỉ dùng thư viện chuẩn, tự dừng JAR khi kết thúc.
./scripts/test-build104-recovery.ps1
# Chạy cả app container với .env đã có secrets, DB sẽ tự migrate V2:
docker compose --profile app up -d --build --wait
```

Suite dùng PostgreSQL/MQTT thật, HTTP cổng ngẫu nhiên và Java WebSocket client. Test tự tạo fixture riêng, không xóa fixture cũ; volume test có thể lớn dần. Test chủ động tạo trigger tạm trong DB test để gây lỗi ngay tại commit rồi gỡ đúng trigger của test. Không chạy đồng thời nhiều suite trên cùng DB test. Mặc định integration test bị bỏ qua nếu không có DB_TEST_ENABLED=true; build skip không chứng minh tính năng tích hợp. MQTT test mặc định localhost:51883, đổi bằng TEST_MQTT_URL. Test timeout được rút còn 1200ms/scan100ms; phép đo cấu hình mặc định 6s nằm trong báo cáo smoke.

Maven cache lỗi C:\.m2\repository: kiểm tra settings hoặc dùng `mvn "-Dmaven.repo.local=$env:USERPROFILE\.m2\repository" -f backend/pom.xml verify`. Không bật DEBUG cho môi trường chứa credential.

## Schema và phạm vi

V1 chỉ tạo schema swiftletcare, giữ nguyên. V2 tạo users, houses, house_memberships, zones, devices, telemetry, count_logs; FK RESTRICT, unique key chống trùng, check constraints, index latest/history và timeout. JPA ddl-auto=validate, không sửa schema; Flyway history ở public. JVM và JDBC dùng UTC. Thay schema mới bằng V3 trở đi, không sửa migration đã áp dụng.

Package management chứa User/House/Zone/Device và repository; iot chứa Telemetry, MQTT ingestion và scheduler; ai chứa CountLog ingestion; api chứa DTO/controller/error; security chứa JWT/login/membership; realtime chứa WebSocket; config chứa seed local. `house_memberships` được thêm để giới hạn quyền đọc, chưa có CRUD membership.

Telemetry store dùng transaction + PostgreSQL ON CONFLICT; so sánh nội dung sau insert-or-existing, không catch unique violation trong transaction đã lỗi. ACK và realtime chỉ sau commit. Duplicate đã lưu nhận ACK nhưng không sinh realtime lần nữa. Không có outbox; retry MQTT và REST bù sau reconnect là bắt buộc.

Chưa triển khai những module ngoài yêu cầu ingest: MQTT command/availability/LWT, actuator/control/config/alert/report/harvest/AI anomaly. JWT MVP có login/access token 15 phút, chưa refresh/logout/revocation. Broker anonymous chỉ local; sản xuất cần TLS/ACL/auth, HTTPS và SECURE_COOKIE=true. Chưa chứng minh offline buffer 24h, NFR tải lớn hoặc UI React end-to-end <=10s.

## Dừng / xử lý lỗi

Ctrl+C cho JAR. `docker compose stop` hoặc `docker compose down` giữ volume. Không dùng down -v nếu cần giữ dữ liệu. Để dừng riêng stack test: `docker compose -p swiftletcare-build104-e2e -f compose.build104-test.yml stop`. Không đổi mật khẩu DB chỉ bằng sửa .env trên volume đã init: dùng credential cũ hoặc đổi trong PostgreSQL. Cổng DB/MQTT bận: đổi POSTGRES_PORT/MQTT_PORT và nạp lại biến ứng dụng. Docker named pipe bị chặn: kiểm tra Docker Desktop/quyền truy cập.
