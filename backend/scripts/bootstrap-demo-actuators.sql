\set ON_ERROR_STOP on
BEGIN;
DO $bootstrap$
DECLARE
  target bigint;
BEGIN
  IF current_database() <> 'swiftletcare_demo' THEN
    RAISE EXCEPTION 'This bootstrap requires database swiftletcare_demo';
  END IF;
  SELECT d.id INTO STRICT target
  FROM swiftletcare.devices d
  JOIN swiftletcare.zones z ON z.id = d.zone_id
  JOIN swiftletcare.houses h ON h.id = z.house_id
  WHERE d.device_id = 'ESP32-LOCAL' AND d.type = 'ESP32'
    AND d.enabled AND z.active AND h.active;
  LOCK TABLE swiftletcare.actuators IN SHARE ROW EXCLUSIVE MODE;
  INSERT INTO swiftletcare.actuators(device_id, kind)
  SELECT target, output.kind
  FROM (VALUES ('FAN'), ('MIST'), ('HEATER'), ('AMPLIFIER')) AS output(kind)
  WHERE NOT EXISTS (
    SELECT 1 FROM swiftletcare.actuators a
    WHERE a.device_id = target AND a.kind = output.kind
  );
END
$bootstrap$;
COMMIT;
