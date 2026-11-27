
-- B3-12: support idempotent snapshot upload.
-- Required by ON CONFLICT(camera_id, sha256).

CREATE UNIQUE INDEX IF NOT EXISTS
    uq_ai_snapshots_camera_sha256
ON swiftletcare.ai_snapshots(camera_id, sha256);
