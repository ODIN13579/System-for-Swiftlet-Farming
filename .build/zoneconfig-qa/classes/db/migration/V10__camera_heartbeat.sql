ALTER TABLE swiftletcare.devices
    ADD COLUMN camera_source_ok BOOLEAN;

CREATE INDEX idx_online_camera_heartbeat
    ON swiftletcare.devices (last_seen_at)
    WHERE type = 'CAMERA' AND enabled AND online;

CREATE UNIQUE INDEX uq_camera_offline_condition
    ON swiftletcare.alerts (device_id)
    WHERE type = 'CAMERA_OFFLINE' AND cleared_at IS NULL;
