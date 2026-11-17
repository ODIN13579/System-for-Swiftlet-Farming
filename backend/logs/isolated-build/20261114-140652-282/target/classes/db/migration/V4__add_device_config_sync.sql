CREATE TABLE IF NOT EXISTS swiftletcare.device_config_sync (
    device_id BIGINT NOT NULL REFERENCES swiftletcare.devices(id),
    zone_id BIGINT NOT NULL REFERENCES swiftletcare.zones(id),
    desired_version INTEGER NOT NULL CHECK (desired_version >= 1),
    applied_version INTEGER,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED', 'STALE')),
    ack_at TIMESTAMPTZ,
    PRIMARY KEY (device_id, zone_id)
);

-- Also upgrades the table if it was created manually using the previous example.
ALTER TABLE swiftletcare.device_config_sync
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ;
