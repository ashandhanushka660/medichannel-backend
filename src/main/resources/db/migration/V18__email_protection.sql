-- ============================================================================
-- MEDICHANNEL
-- V18: Email-at-rest protection and forced password changes
-- ============================================================================
-- users.email currently holds lowercase plaintext and is covered by a
-- uniqueness constraint and a lowercase check constraint. From this version
-- the column holds the AES-256-GCM "v1:" envelope produced by the application.
-- Equality lookups and uniqueness move to an HMAC-SHA256 lookup column.
--
-- The plaintext backup column exists only until V20 is explicitly applied
-- after the Java backfill runner has been verified. Flyway must NOT run V20
-- in the same deploy cycle as this migration.

-- The old constraints reject ciphertext (mixed case, longer values).
ALTER TABLE users
    DROP CONSTRAINT IF EXISTS uq_users_email;

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS chk_users_email_normalized;

-- Ciphertext for a 254-character email is at most 512 characters.
ALTER TABLE users
    ALTER COLUMN email TYPE VARCHAR(512);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_lookup_hash VARCHAR(64);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_plaintext_backup VARCHAR(254);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS password_change_required BOOLEAN NOT NULL DEFAULT FALSE;

-- NULLs are allowed until the backfill runner has populated every row.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lookup_hash
    ON users(email_lookup_hash)
    WHERE email_lookup_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_password_change_required
    ON users(password_change_required)
    WHERE password_change_required;
