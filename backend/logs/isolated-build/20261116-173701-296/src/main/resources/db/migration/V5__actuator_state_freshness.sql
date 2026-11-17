ALTER TABLE swiftletcare.actuators
    ADD COLUMN state_received_at TIMESTAMPTZ,
    ADD COLUMN safety_override BOOLEAN;
