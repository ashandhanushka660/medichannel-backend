-- =============================================
-- MEDICHANNEL
-- V26: Two published daily sessions
-- =============================================

-- Each doctor now has two bookable windows per day:
--   Morning   07:00-09:30, 30 patients, 5 minutes each
--   Afternoon 12:00-14:30, 30 patients, 5 minutes each
-- The 09:30-12:00 lunch gap sits between them, and the combined daily
-- ceiling is 60 patients. The afternoon session is created through the
-- normal manager flow, which enforces the 60/day rule.

-- Future rows in the old single-session shape are retimed to the morning
-- window. Guard first so an unexpected roster is never silently corrupted.
DO $$
DECLARE
    invalid_rows INTEGER;
BEGIN
    SELECT COUNT(*) INTO invalid_rows
    FROM (
        SELECT doctor_id, session_date
        FROM doctor_sessions
        WHERE session_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Colombo')::date
          AND status <> 'CANCELLED'
        GROUP BY doctor_id, session_date
        HAVING COUNT(*) > 1
    ) duplicate_rosters;

    IF invalid_rows > 0 THEN
        RAISE EXCEPTION
            'V26 needs at most one future non-cancelled session per doctor/day';
    END IF;

    SELECT COUNT(*) INTO invalid_rows
    FROM appointments a
    INNER JOIN doctor_sessions s ON s.id = a.session_id
    WHERE s.session_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Colombo')::date
      AND s.status <> 'CANCELLED'
      AND s.start_time = TIME '07:00'
      AND s.end_time = TIME '16:00'
      AND a.appointment_number > 30;

    IF invalid_rows > 0 THEN
        RAISE EXCEPTION
            'V26 cannot retime a future session with an appointment number above 30';
    END IF;
END $$;

UPDATE doctor_sessions
SET start_time = TIME '07:00',
    end_time = TIME '09:30',
    minutes_per_patient = 5,
    max_appointments = 30,
    delay_minutes = 0
WHERE session_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Colombo')::date
  AND status <> 'CANCELLED'
  AND start_time = TIME '07:00'
  AND end_time = TIME '16:00';

-- Past and cancelled rows are deliberately untouched: they remain part of
-- the hospital's audit/history record.
