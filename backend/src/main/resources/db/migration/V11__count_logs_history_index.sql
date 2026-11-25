-- Count history combines both aggregation types and pages by period_start, id.
-- The existing unique key continues to serve ingestion idempotency.
CREATE INDEX idx_count_logs_device_period_id
    ON swiftletcare.count_logs (device_id, period_start DESC, id DESC);
