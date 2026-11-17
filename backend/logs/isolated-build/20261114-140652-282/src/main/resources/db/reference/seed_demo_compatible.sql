-- SwiftletCare compatible demo seed for an already populated V1/V2 database.
-- Safe to re-run: uses NOT EXISTS / ON CONFLICT and resolves current IDs dynamically.
-- Synthetic demo records only; does not create/overwrite users, houses, zones or devices.
-- Run only after Flyway V3 has succeeded.

BEGIN;

-- One configuration per existing zone.
INSERT INTO swiftletcare.zone_configs
    (zone_id, humidity_on, humidity_off, temp_min, temp_max, temp_hysteresis,
     co2_min, co2_max, light_min, light_max, telemetry_interval_sec, heartbeat_interval_sec)
SELECT z.id, 75, 82, 24, 31, 1, 0, 1500, 5, 500, 5, 2
FROM swiftletcare.zones z
WHERE NOT EXISTS (
    SELECT 1 FROM swiftletcare.zone_configs c WHERE c.zone_id = z.id
);

-- Attach demo sensors to existing devices without duplicating the same kind.
INSERT INTO swiftletcare.sensors (device_id, kind, unit)
SELECT d.id, x.kind, x.unit
FROM swiftletcare.devices d
CROSS JOIN (VALUES
    ('TEMPERATURE', '°C'),
    ('HUMIDITY', '%'),
    ('CO2', 'ppm'),
    ('LIGHT', 'lux')
) AS x(kind, unit)
WHERE d.type = 'ESP32'
  AND NOT EXISTS (
      SELECT 1 FROM swiftletcare.sensors s
      WHERE s.device_id = d.id AND s.kind = x.kind
  );

-- Actuators are attached only to existing ESP32 devices.
INSERT INTO swiftletcare.actuators (device_id, kind, mode, "on")
SELECT d.id, x.kind, 'AUTO', FALSE
FROM swiftletcare.devices d
CROSS JOIN (VALUES ('MIST'), ('FAN')) AS x(kind)
WHERE d.type = 'ESP32'
  AND NOT EXISTS (
      SELECT 1 FROM swiftletcare.actuators a
      WHERE a.device_id = d.id AND a.kind = x.kind
  );

-- Add three synthetic telemetry samples per ESP32, using each device's current DB ID.
INSERT INTO swiftletcare.telemetry
    (device_id, event_id, "timestamp", received_at, temperature, humidity, co2, light, sensor_status)
SELECT d.id,
       'swiftletcare-demo-v1-' || d.id::text || '-' || x.sample_no::text,
       CURRENT_TIMESTAMP - (x.sample_no * INTERVAL '5 minutes'),
       CURRENT_TIMESTAMP - (x.sample_no * INTERVAL '5 minutes'),
       28.0 + x.sample_no * 0.4,
       78.0 - x.sample_no * 0.8,
       650 + x.sample_no * 35,
       35 + x.sample_no * 5,
       'OK'
FROM swiftletcare.devices d
CROSS JOIN (VALUES (1), (2), (3)) AS x(sample_no)
WHERE d.type = 'ESP32'
ON CONFLICT (device_id, event_id) DO NOTHING;

-- Add two synthetic hourly bird-count records per existing camera.
INSERT INTO swiftletcare.count_logs
    (device_id, period_start, period_end, entry_count, exit_count, detect_count,
     aggregation_type, processed_at)
SELECT d.id,
       date_trunc('hour', CURRENT_TIMESTAMP) - (x.hour_offset * INTERVAL '1 hour'),
       date_trunc('hour', CURRENT_TIMESTAMP) - ((x.hour_offset - 1) * INTERVAL '1 hour'),
       8 + x.hour_offset, 3 + x.hour_offset, 11 + 2 * x.hour_offset,
       'HOURLY', CURRENT_TIMESTAMP
FROM swiftletcare.devices d
CROSS JOIN (VALUES (1), (2)) AS x(hour_offset)
WHERE d.type = 'CAMERA'
  AND NOT EXISTS (
      SELECT 1 FROM swiftletcare.count_logs c
      WHERE c.device_id = d.id
        AND c.aggregation_type = 'HOURLY'
        AND c.period_start = date_trunc('hour', CURRENT_TIMESTAMP) - (x.hour_offset * INTERVAL '1 hour')
        AND c.period_end = date_trunc('hour', CURRENT_TIMESTAMP) - ((x.hour_offset - 1) * INTERVAL '1 hour')
  );

-- One synthetic AI anomaly and linked alert per existing camera, if not already seeded.
WITH camera AS (
    SELECT d.id AS device_id, d.zone_id
    FROM swiftletcare.devices d
    WHERE d.type = 'CAMERA'
),
new_anomalies AS (
    INSERT INTO swiftletcare.anomaly_events (device_id, species, confidence, detected_at, snapshot_path)
    SELECT c.device_id, 'PREDATOR', 0.91, CURRENT_TIMESTAMP - INTERVAL '10 minutes', NULL
    FROM camera c
    WHERE NOT EXISTS (
        SELECT 1 FROM swiftletcare.anomaly_events a
        WHERE a.device_id = c.device_id
          AND a.species = 'PREDATOR'
          AND a.detected_at > CURRENT_TIMESTAMP - INTERVAL '1 day'
    )
    RETURNING id, device_id
)
INSERT INTO swiftletcare.alerts (zone_id, anomaly_event_id, type, level, status, message, created_at)
SELECT c.zone_id, a.id, 'PREDATOR_DETECTED', 'WARNING', 'NEW',
       'DEMO: phát hiện bất thường từ camera (dữ liệu mô phỏng).',
       CURRENT_TIMESTAMP - INTERVAL '10 minutes'
FROM new_anomalies a
JOIN camera c ON c.device_id = a.device_id;

-- Add a sample harvest record per existing zone only when no recent demo row exists.
INSERT INTO swiftletcare.harvest_logs (zone_id, "date", yield_kg, note)
SELECT z.id, CURRENT_DATE, 1.25, 'DEMO: sản lượng mô phỏng'
FROM swiftletcare.zones z
WHERE NOT EXISTS (
    SELECT 1 FROM swiftletcare.harvest_logs h
    WHERE h.zone_id = z.id
      AND h."date" = CURRENT_DATE
      AND h.note = 'DEMO: sản lượng mô phỏng'
);

-- Add WEB notification rows for current alerts and active users, without duplicates.
INSERT INTO swiftletcare.notification_deliveries (alert_id, user_id, channel, status)
SELECT a.id, u.id, 'WEB', 'PENDING'
FROM swiftletcare.alerts a
CROSS JOIN swiftletcare.users u
WHERE u.active = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM swiftletcare.notification_deliveries n
      WHERE n.alert_id = a.id AND n.user_id = u.id AND n.channel = 'WEB'
  );

COMMIT;
