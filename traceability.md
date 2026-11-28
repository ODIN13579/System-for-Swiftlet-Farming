# Traceability BUILD3-03

| Nguồn yêu cầu | Implementation | Kiểm thử hoặc bằng chứng | Jira |
| --- | --- | --- | --- |
| FR-REP thống kê môi trường/CountLog/CSV | StatisticsController, DeviceController và CountReadController | StatisticsIntegrationTest, SQL đối chứng và CSV từ QA DB | B3-20 |
| Alert engine, workflow, House READ/WRITE | AlertEngine, AlertController, HouseAlertRealtime, Access | AlertEngineIntegrationTest, AlertWorkflowIntegrationTest, HouseRoleReassignmentIntegrationTest | B3-21 |
| Ảnh, Predator retry, ownership | AiPredatorController/Service | AiPredatorIntegrationTest | B3-21 |
| Account/expiry/role/revocation | SecurityConfig, Access, AccountController | AccountManagementIntegrationTest, IngestionIntegrationTest | B3-21 |
| Regression Command/ACK, ZoneConfig, Telemetry ACK, AI CountLog | ControlCommands, DeviceConfigSync, MqttGateway, CountController | ControlCommandsIntegrationTest, ConfigAckIntegrationTest, IngestionIntegrationTest, Build2ManagementIntegrationTest | B3-21 |
| Alert ≤5s tới React, tải 50, auth và AI traffic | Backend/React, measure-build3.py | [NFR v2](docs/tests/nfr-build3-v2.md) | B3-22 |
| Hợp đồng và trình tự | [Alert v1.2](docs/api/alerts-v1.2.md), [sequence](docs/sequence/build3-alerts.md) | Đối chiếu REST/WS/MQTT, trạng thái OPEN trong hợp đồng | B3-23 |
| Bug, rủi ro, freeze | [Bug](docs/tests/bug-tracker.md), [Risk](docs/tests/risk-log.md), [Freeze](docs/tests/freeze-checklist.md) | Review owner và bằng chứng | B3-24 |
| PDF khớp số liệu | DEFERRED | Gate B3-20/21/22 | B3-25 |
