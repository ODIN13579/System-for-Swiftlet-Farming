-- SwiftletCare demo baseline seed for the current V1/V2/V3 schema.
-- Purpose: create the prerequisite demo users, houses, memberships, zones, devices,
-- sensors, actuators, and zone configurations before running the optional demo seed.
-- Prerequisite: Flyway V1, V2, and V3 have succeeded.
-- Safe to re-run: resolves IDs dynamically and checks for existing rows.
-- Synthetic demo data only. Do not use as production credentials/data.

BEGIN;

-- Demo users. The password hash is copied from the previous synthetic fixture.
-- Confirm the corresponding demo password in your project documentation before sharing.
INSERT INTO swiftletcare.users
    (email, password_hash, full_name, phone, role, active)
SELECT x.email, x.password_hash, x.full_name, NULL, x.role, TRUE
FROM (VALUES
    ('owner.demo@swiftletcare.local',   '$2y$10$fUOdYbYAGKpu7BMts.iUM.JBGWypSsJu/eb36Ms7v/mwWGfVoR8pG', 'Chủ nhà yến A', 'OWNER'),
    ('admin.demo@swiftletcare.local',   '$2y$10$fUOdYbYAGKpu7BMts.iUM.JBGWypSsJu/eb36Ms7v/mwWGfVoR8pG', 'Quản trị viên toàn hệ thống', 'ADMIN'),
    ('manager.demo@swiftletcare.local', '$2y$10$fUOdYbYAGKpu7BMts.iUM.JBGWypSsJu/eb36Ms7v/mwWGfVoR8pG', 'Quản lý nhà yến A', 'OWNER'),
    ('viewer.demo@swiftletcare.local',  '$2y$10$fUOdYbYAGKpu7BMts.iUM.JBGWypSsJu/eb36Ms7v/mwWGfVoR8pG', 'Người theo dõi nhà yến A', 'OWNER'),
    ('owner2.demo@swiftletcare.local',  '$2y$10$fUOdYbYAGKpu7BMts.iUM.JBGWypSsJu/eb36Ms7v/mwWGfVoR8pG', 'Chủ nhà yến B', 'OWNER'),
    ('viewer2.demo@swiftletcare.local', '$2y$10$fUOdYbYAGKpu7BMts.iUM.JBGWypSsJu/eb36Ms7v/mwWGfVoR8pG', 'Người theo dõi nhà yến B', 'OWNER')
) AS x(email, password_hash, full_name, role)
WHERE NOT EXISTS (
    SELECT 1 FROM swiftletcare.users u WHERE u.email = x.email
);

-- Demo houses.
INSERT INTO swiftletcare.houses (name, address, description, active)
SELECT x.name, x.address, x.description, TRUE
FROM (VALUES
    ('Nhà yến Demo A', 'Đồng Nai – vị trí giả lập', 'Nhà yến mẫu A phục vụ phát triển SwiftletCare'),
    ('Nhà yến Demo B', 'Bình Dương – vị trí giả lập', 'Nhà yến mẫu B để kiểm thử phân quyền nhiều nhà yến')
) AS x(name, address, description)
WHERE NOT EXISTS (
    SELECT 1 FROM swiftletcare.houses h WHERE h.name = x.name
);

-- Memberships: map by email and house name instead of assuming fixed IDs.
INSERT INTO swiftletcare.house_memberships
    (user_id, house_id, role, active, joined_at)
SELECT u.id, h.id, x.membership_role, TRUE, TIMESTAMPTZ '2026-10-16 01:00:00+00'
FROM (VALUES
    ('owner.demo@swiftletcare.local',   'Nhà yến Demo A', 'OWNER'),
    ('manager.demo@swiftletcare.local', 'Nhà yến Demo A', 'ADMIN'),
    ('viewer.demo@swiftletcare.local',  'Nhà yến Demo A', 'VIEWER'),
    ('owner2.demo@swiftletcare.local',  'Nhà yến Demo B', 'OWNER'),
    ('viewer2.demo@swiftletcare.local', 'Nhà yến Demo B', 'VIEWER')
) AS x(email, house_name, membership_role)
JOIN swiftletcare.users u ON u.email = x.email
JOIN swiftletcare.houses h ON h.name = x.house_name
WHERE NOT EXISTS (
    SELECT 1 FROM swiftletcare.house_memberships m
    WHERE m.user_id = u.id AND m.house_id = h.id
);

-- Demo zones.
INSERT INTO swiftletcare.zones (house_id, name, floor_label, area_m2, active)
SELECT h.id, x.name, x.floor_label, x.area_m2, TRUE
FROM (VALUES
    ('Nhà yến Demo A', 'Khu vực tầng 1', 'Tầng 1', 120.0::DOUBLE PRECISION),
    ('Nhà yến Demo A', 'Khu vực tầng 2', 'Tầng 2', 100.0::DOUBLE PRECISION),
    ('Nhà yến Demo B', 'Khu vực nuôi B', 'Tầng 1', 135.0::DOUBLE PRECISION)
) AS x(house_name, name, floor_label, area_m2)
JOIN swiftletcare.houses h ON h.name = x.house_name
WHERE NOT EXISTS (
    SELECT 1 FROM swiftletcare.zones z
    WHERE z.house_id = h.id AND z.name = x.name
);

-- Demo devices. Device identifiers are unique and stable.
INSERT INTO swiftletcare.devices
    (zone_id, device_id, name, type, enabled, online, last_seen_at)
SELECT z.id, x.device_id, x.name, x.type, TRUE, FALSE, NULL
FROM (VALUES
    ('Nhà yến Demo A', 'Khu vực tầng 1', 'ESP32-001', 'ESP32 cảm biến tầng 1', 'ESP32'),
    ('Nhà yến Demo A', 'Khu vực tầng 2', 'ESP32-002', 'ESP32 cảm biến tầng 2', 'ESP32'),
    ('Nhà yến Demo A', 'Khu vực tầng 1', 'CAM-001', 'Camera cửa ra/vào nhà A', 'CAMERA'),
    ('Nhà yến Demo B', 'Khu vực nuôi B', 'ESP32-003', 'ESP32 cảm biến nhà B', 'ESP32'),
    ('Nhà yến Demo B', 'Khu vực nuôi B', 'CAM-002', 'Camera cửa ra/vào nhà B', 'CAMERA')
) AS x(house_name, zone_name, device_id, name, type)
JOIN swiftletcare.houses h ON h.name = x.house_name
JOIN swiftletcare.zones z ON z.house_id = h.id AND z.name = x.zone_name
WHERE NOT EXISTS (
    SELECT 1 FROM swiftletcare.devices d WHERE d.device_id = x.device_id
);

-- One configuration per demo zone. V3 adds the zone_configs table.
INSERT INTO swiftletcare.zone_configs
    (zone_id, humidity_on, humidity_off, temp_min, temp_max, temp_hysteresis,
     co2_min, co2_max, light_min, light_max, telemetry_interval_sec, heartbeat_interval_sec)
SELECT z.id, 74, 78, 20, 31, 1, 400, 2000, 0, 100, 5, 2
FROM swiftletcare.zones z
JOIN swiftletcare.houses h ON h.id = z.house_id
WHERE h.name IN ('Nhà yến Demo A', 'Nhà yến Demo B')
  AND NOT EXISTS (
      SELECT 1 FROM swiftletcare.zone_configs c WHERE c.zone_id = z.id
  );

-- Four sensors per demo ESP32, without duplicating a device/kind pair.
INSERT INTO swiftletcare.sensors (device_id, kind, unit)
SELECT d.id, x.kind, x.unit
FROM swiftletcare.devices d
CROSS JOIN (VALUES
    ('TEMPERATURE', 'C'),
    ('HUMIDITY', '%RH'),
    ('CO2', 'ppm'),
    ('LIGHT', 'lux')
) AS x(kind, unit)
WHERE d.type = 'ESP32'
  AND d.device_id IN ('ESP32-001', 'ESP32-002', 'ESP32-003')
  AND NOT EXISTS (
      SELECT 1 FROM swiftletcare.sensors s
      WHERE s.device_id = d.id AND s.kind = x.kind
  );

-- Four actuators per demo ESP32, without duplicating a device/kind pair.
INSERT INTO swiftletcare.actuators (device_id, kind, mode, "on", manual_until)
SELECT d.id, x.kind, 'AUTO', FALSE, NULL
FROM swiftletcare.devices d
CROSS JOIN (VALUES ('MIST'), ('FAN'), ('HEATER'), ('AMPLIFIER')) AS x(kind)
WHERE d.type = 'ESP32'
  AND d.device_id IN ('ESP32-001', 'ESP32-002', 'ESP32-003')
  AND NOT EXISTS (
      SELECT 1 FROM swiftletcare.actuators a
      WHERE a.device_id = d.id AND a.kind = x.kind
  );

COMMIT;
