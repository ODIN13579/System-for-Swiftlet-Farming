ALTER TABLE swiftletcare.users
ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0
CHECK (token_version >= 0);

-- Enforce case-insensitive uniqueness of email.
CREATE UNIQUE INDEX uq_users_email_lower
ON swiftletcare.users (lower(email));