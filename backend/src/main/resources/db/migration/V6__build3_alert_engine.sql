
-- BUILD3 B3-10
-- Keep existing V1-V5 migrations unchanged.

ALTER TABLE swiftletcare.alerts
    ADD COLUMN device_id BIGINT
        REFERENCES swiftletcare.devices(id) ON DELETE RESTRICT,
    ADD COLUMN cleared_at TIMESTAMPTZ;

CREATE INDEX idx_alerts_type_zone_cleared
    ON swiftletcare.alerts
    (zone_id, type, created_at DESC);

-- Tracks duration of manual out-of-range conditions.
CREATE TABLE swiftletcare.alert_manual_conditions (
    device_id BIGINT PRIMARY KEY
        REFERENCES swiftletcare.devices(id) ON DELETE CASCADE,
    condition_code VARCHAR(40) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP
);

-- Tracks sensor faults until recovery is confirmed.
CREATE TABLE swiftletcare.alert_sensor_faults (
    device_id BIGINT NOT NULL
        REFERENCES swiftletcare.devices(id) ON DELETE CASCADE,
    sensor VARCHAR(80) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_id, sensor)
);
