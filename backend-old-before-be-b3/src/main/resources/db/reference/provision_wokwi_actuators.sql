-- Run once (or rerun) in the local Wokwi demo database, after Flyway.
-- Replace the device code below if the simulator uses another ESP32.
BEGIN;
SELECT id,device_id FROM swiftletcare.devices
WHERE type='ESP32' AND device_id='ESP32-HOUSE01-ZONE01' FOR UPDATE;

INSERT INTO swiftletcare.actuators(device_id,kind)
SELECT d.id, k.kind
FROM swiftletcare.devices d
-- CROSS JOIN (VALUES ('FAN'), ('MIST'), ('HEATER'), ('AMPLIFIER')) AS k(kind)
CROSS JOIN (VALUES ('FAN')) AS k(kind)
WHERE d.type='ESP32' AND d.device_id='ESP32-HOUSE01-ZONE01'
  AND NOT EXISTS (
    SELECT 1 FROM swiftletcare.actuators a WHERE a.device_id=d.id AND a.kind=k.kind
  );

SELECT a.id AS actuator_id,a.kind,d.id AS device_pk,d.device_id,z.id AS zone_id,z.house_id
FROM swiftletcare.actuators a JOIN swiftletcare.devices d ON d.id=a.device_id
JOIN swiftletcare.zones z ON z.id=d.zone_id
WHERE d.device_id='ESP32-HOUSE01-ZONE01'
ORDER BY a.kind;

COMMIT;