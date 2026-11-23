-- ============================================================================
-- MEDICHANNEL
-- V19: Audit trail hardening
-- ============================================================================
-- * Audit events identify actors by immutable user id, never by plaintext email.
-- * Consultation record opens carry the appointment id they refer to.
-- * audit_logs is append-only: UPDATE/DELETE/TRUNCATE are rejected by triggers.

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS user_id UUID,
    ADD COLUMN IF NOT EXISTS appointment_id UUID;

-- Best-effort backfill from the legacy plaintext email column. Accounts whose
-- email has since changed cannot be attributed and are deliberately left NULL.
-- V18 may still expose a plaintext backup column while users.email itself has
-- been encrypted, so use it when present without making V19 depend on V18's
-- internal column layout.
DO $do$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'users'
          AND column_name = 'email_plaintext_backup'
    ) THEN
        EXECUTE $sql$
            UPDATE audit_logs AS a
            SET user_id = u.id
            FROM users AS u
            WHERE a.user_id IS NULL
              AND a.user_email IS NOT NULL
              AND (
                  lower(btrim(a.user_email)) = lower(btrim(u.email))
                  OR (
                      u.email_plaintext_backup IS NOT NULL
                      AND lower(btrim(a.user_email)) =
                          lower(btrim(u.email_plaintext_backup))
                  )
              )
        $sql$;
    ELSE
        EXECUTE $sql$
            UPDATE audit_logs AS a
            SET user_id = u.id
            FROM users AS u
            WHERE a.user_id IS NULL
              AND a.user_email IS NOT NULL
              AND lower(btrim(a.user_email)) = lower(btrim(u.email))
        $sql$;
    END IF;
END
$do$;

-- Remove the plaintext identifier entirely; the application no longer writes it.
ALTER TABLE audit_logs DROP COLUMN IF EXISTS user_email;

-- The old default hardcoded a loopback address. Auditing must record the real
-- client address when one is available, and NULL/"unknown" otherwise.
ALTER TABLE audit_logs ALTER COLUMN ip_address DROP DEFAULT;

CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id
    ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_appointment_id
    ON audit_logs(appointment_id);

-- ----------------------------------------------------------------------------
-- Append-only enforcement
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_logs_append_only_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only: % is not permitted', TG_OP
        USING ERRCODE = '23000';
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_logs_no_update ON audit_logs;
CREATE TRIGGER trg_audit_logs_no_update
    BEFORE UPDATE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION audit_logs_append_only_guard();

DROP TRIGGER IF EXISTS trg_audit_logs_no_delete ON audit_logs;
CREATE TRIGGER trg_audit_logs_no_delete
    BEFORE DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION audit_logs_append_only_guard();

DROP TRIGGER IF EXISTS trg_audit_logs_no_truncate ON audit_logs;
CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION audit_logs_append_only_guard();
