# SwiftletCare — API Endpoint Catalog

> Sinh từ cùng danh sách với `openapi.yaml`; 47 thao tác / 36 URL. Mọi URL, enum và schema đang ở trạng thái **review** trước sign-off P4/P5/P1/P2.

**Quy ước:** `deviceId` trong REST path là `devices.id: BIGINT`; JSON REST `eventId` map MQTT/SQL `event_id`. `commandId` là chuỗi định danh lệnh. `MVP` là ưu tiên BUILD đề xuất.

**Trạng thái BUILD1-04:** cột cuối phân biệt code đã triển khai với thiết kế toàn hệ thống. Chỉ 6 REST operation có trạng thái `Implemented BUILD1-04`; các dòng còn lại được giữ cho giai đoạn sau. Contract chạy thực tế: [build104-mvp.md](build104-mvp.md). Plain WebSocket `/ws/devices/{deviceId}` được mô tả riêng, không tính vào 47 REST operation.

## Authentication

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `POST` | `/api/v1/auth/login` | `LoginRequest` | `AccessTokenResponse` | MVP | Implemented BUILD1-04 |
| `POST` | `/api/v1/auth/refresh` | `RefreshRequest` | `TokenPairResponse` | Extended | Design only / outside BUILD1-04 |
| `POST` | `/api/v1/auth/logout` | `LogoutRequest` | `—` | Extended | Design only / outside BUILD1-04 |

## Users

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/users/me` | `—` | `UserResponse` | MVP | Design only / outside BUILD1-04 |
| `PATCH` | `/api/v1/users/me` | `UserUpdateRequest` | `UserResponse` | Extended | Design only / outside BUILD1-04 |

## Houses

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/houses` | `—` | `HouseListResponse` | MVP | Design only / outside BUILD1-04 |
| `POST` | `/api/v1/houses` | `HouseCreateRequest` | `HouseResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/houses/{houseId}` | `—` | `HouseResponse` | MVP | Design only / outside BUILD1-04 |
| `PATCH` | `/api/v1/houses/{houseId}` | `HouseUpdateRequest` | `HouseResponse` | Extended | Design only / outside BUILD1-04 |

## Memberships

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/houses/{houseId}/members` | `—` | `MembershipListResponse` | Extended | Design only / outside BUILD1-04 |
| `POST` | `/api/v1/houses/{houseId}/members` | `MembershipCreateRequest` | `HouseMembershipResponse` | Extended | Design only / outside BUILD1-04 |
| `PATCH` | `/api/v1/houses/{houseId}/members/{membershipId}` | `MembershipUpdateRequest` | `HouseMembershipResponse` | Extended | B3-16, đổi role bởi House OWNER hoặc Global ADMIN |

## Zones

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/houses/{houseId}/zones` | `—` | `ZoneListResponse` | MVP | Design only / outside BUILD1-04 |
| `POST` | `/api/v1/houses/{houseId}/zones` | `ZoneCreateRequest` | `ZoneResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/zones/{zoneId}` | `—` | `ZoneResponse` | MVP | Design only / outside BUILD1-04 |
| `PATCH` | `/api/v1/zones/{zoneId}` | `ZoneUpdateRequest` | `ZoneResponse` | Extended | Design only / outside BUILD1-04 |

## Devices

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/zones/{zoneId}/devices` | `—` | `DeviceListResponse` | MVP | Implemented BUILD1-04 |
| `POST` | `/api/v1/zones/{zoneId}/devices` | `DeviceCreateRequest` | `DeviceResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/devices/{deviceId}` | `—` | `DeviceResponse` | MVP | Implemented BUILD1-04 |
| `PATCH` | `/api/v1/devices/{deviceId}` | `DeviceUpdateRequest` | `DeviceResponse` | Extended | Design only / outside BUILD1-04 |

## Actuators

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/devices/{deviceId}/actuators` | `—` | `ActuatorListResponse` | MVP | Design only / outside BUILD1-04 |

## Telemetry

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/devices/{deviceId}/telemetry/latest` | `—` | `TelemetryResponse` | MVP | Implemented BUILD1-04 |
| `GET` | `/api/v1/devices/{deviceId}/telemetry` | `—` | `TelemetryListResponse` | MVP | Implemented BUILD1-04 |

## Zone Configuration

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/zones/{zoneId}/config` | `—` | `ZoneConfigResponse` | MVP | Design only / outside BUILD1-04 |
| `PUT` | `/api/v1/zones/{zoneId}/config` | `ZoneConfigUpsertRequest` | `ZoneConfigResponse` | MVP | Design only / outside BUILD1-04 |

## Control Commands

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `POST` | `/api/v1/actuators/{actuatorId}/commands` | `ControlCommandCreateRequest` | `ControlCommandResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/commands/{commandId}` | `—` | `ControlCommandResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/actuators/{actuatorId}/commands` | `—` | `ControlCommandListResponse` | Extended | Design only / outside BUILD1-04 |

## AI Events

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `POST` | `/api/v1/internal/ai/count-logs` | `CountLogIngestRequest` | `CountLogResponse` | MVP | Implemented BUILD1-04 |
| `POST` | `/api/v1/internal/ai/anomalies` | `AnomalyEventIngestRequest` | `AnomalyEventResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/devices/{deviceId}/count-logs` | `—` | `CountLogListResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/zones/{zoneId}/anomalies` | `—` | `AnomalyListResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/anomalies/{anomalyId}` | `—` | `AnomalyEventResponse` | MVP | Design only / outside BUILD1-04 |

## Alerts

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/alerts` | `—` | `AlertListResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/alerts/{alertId}` | `—` | `AlertResponse` | MVP | Design only / outside BUILD1-04 |
| `PATCH` | `/api/v1/alerts/{alertId}/status` | `AlertStatusUpdateRequest` | `AlertResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/alerts/{alertId}/history` | `—` | `AlertHistoryListResponse` | MVP | Design only / outside BUILD1-04 |

## Notifications

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/notifications` | `—` | `NotificationListResponse` | Extended | Design only / outside BUILD1-04 |

## Harvest

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/zones/{zoneId}/harvest-logs` | `—` | `HarvestLogListResponse` | MVP | Design only / outside BUILD1-04 |
| `POST` | `/api/v1/zones/{zoneId}/harvest-logs` | `HarvestLogCreateRequest` | `HarvestLogResponse` | MVP | Design only / outside BUILD1-04 |

## Statistics & Reports

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/reports/environment` | `—` | `EnvironmentStatsResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/reports/bird-count` | `—` | `BirdCountStatsResponse` | MVP | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/reports/productivity-correlation` | `—` | `CorrelationResponse` | Extended | Design only / outside BUILD1-04 |
| `POST` | `/api/v1/reports` | `ReportCreateRequest` | `ReportResponse` | Extended | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/reports/{reportId}` | `—` | `ReportResponse` | Extended | Design only / outside BUILD1-04 |
| `GET` | `/api/v1/reports/{reportId}/download` | `—` | `—` | Extended | Design only / outside BUILD1-04 |

## Dashboard

| Method | Endpoint | Request DTO | Response DTO | Ưu tiên | Implementation status |
|---|---|---|---|---|---|
| `GET` | `/api/v1/dashboard/summary` | `—` | `DashboardSummaryResponse` | MVP | Design only / outside BUILD1-04 |

## Quy tắc quan trọng theo endpoint

1. `POST /auth/login`: trả JWT; refresh/logout phụ thuộc chính sách phiên của P4.
2. `POST /houses`: body có `initialZone` là **giải pháp đề xuất**, để tương thích `House 1..* Zone` từ Class; chưa chốt với P3/P4.
3. `GET /devices/{deviceId}/telemetry/latest`: sắp theo `timestamp DESC`, `receivedAt DESC`, tránh gửi bù muộn trở thành “mới nhất”.
4. `GET .../telemetry`: lọc UTC `from` inclusive, `to` exclusive, phân trang.
5. `PUT /zones/{zoneId}/config`: Backend lưu/cập nhật phiên bản, không tự tuyên bố ESP32 đã áp dụng.
6. `POST /actuators/{actuatorId}/commands`: `202` là đã tiếp nhận/lưu lệnh chứ không phải đã bật relay. `SET_MODE` vs `SET_STATE` kiểm tra XOR; `SET_STATE` yêu cầu MANUAL.
7. `GET /commands/{commandId}`: path là commandId chuỗi, không phải `ControlCommand.id` BIGINT.
8. BUILD1-04 chỉ triển khai `POST /api/v1/internal/ai/count-logs`, auth `X-Service-Key`. Khóa aggregate bất biến `(deviceId,aggregationType,periodStart,periodEnd)`; mới 201, retry giống 200, khác counts/processedAt 409. Anomaly/raw-event idempotency vẫn thuộc thiết kế giai đoạn sau.
9. `PATCH /alerts/{alertId}/status`: ghi `AlertHistory` cùng transaction với update `Alert`.
10. `GET /reports/productivity-correlation`: correlation mang tính thống kê khám phá, không chứng minh quan hệ nhân quả; cách ghép thời gian/dữ liệu TBD.
11. REST lấy lịch sử, WebSocket đẩy cập nhật; sau reconnect React tải REST để đồng bộ.

