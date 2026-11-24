-- Raise the published per-session patient ceiling from 48 to the assignment's
-- 1-60 range, and make five minutes the default patient slot. The existing
-- check constraints already permit 5-30 minute slots.
UPDATE doctor_sessions
SET max_appointments = 60,
    minutes_per_patient = 5
WHERE max_appointments <> 60
   OR minutes_per_patient <> 5;

-- Doctor sessions are hospital records. Staff may cancel a session by changing
-- its status, but the row must never be removed from the database.
CREATE OR REPLACE FUNCTION protect_doctor_session_history()
RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Doctor sessions are retained; change the session status instead.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_protect_doctor_session_history ON doctor_sessions;
CREATE TRIGGER trg_protect_doctor_session_history
BEFORE DELETE ON doctor_sessions
FOR EACH ROW EXECUTE FUNCTION protect_doctor_session_history();
