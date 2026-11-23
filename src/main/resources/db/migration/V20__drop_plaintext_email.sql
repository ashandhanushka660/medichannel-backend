-- ============================================================================
-- MEDICHANNEL
-- V20: Drop the legacy plaintext email backup (POST-BACKFILL ONLY)
-- ============================================================================
-- DO NOT COPY THIS FILE INTO db/migration YET.
--
-- Flyway runs SQL migrations before Spring ApplicationRunners, so applying
-- this in the same deploy as V18 would delete the plaintext before
-- UserEmailEncryptionMigrationRunner has encrypted it.
--
-- Before promoting this migration, verify in the target database:
--
--   SELECT COUNT(*) FROM users WHERE email NOT LIKE 'v1:%';              -- 0
--   SELECT COUNT(*) FROM users WHERE email_lookup_hash IS NULL;         -- 0
--   SELECT COUNT(*) FROM users WHERE email_plaintext_backup IS NOT NULL; -- 55
--
-- Then move this file into src/main/resources/db/migration/ and redeploy.

ALTER TABLE users
    ALTER COLUMN email_lookup_hash SET NOT NULL;

ALTER TABLE users
    DROP COLUMN IF EXISTS email_plaintext_backup;
