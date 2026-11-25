-- =============================================
-- MEDICHANNEL
-- V27: Published Morning / Evening windows
-- =============================================

-- Each doctor now has two bookable windows per day:
--   Morning 07:00-11:00, 30 patients, 5 minutes each
--   Evening 13:00-15:00, 30 patients, 5 minutes each
-- The 11:00-13:00 lunch break sits between them. No appointment number is
-- issued during that break, and the combined daily ceiling remains 60.

-- Retiming is limited to future, non-cancelled rows in the old exact windows.
-- Past and cancelled rows remain part of the hospital's audit history.

DO $$
DECLARE
    invalid_rows INTEGER;
BEGIN
    SELECT COUNT(*) INTO invalid_rows
    FROM appointments a
    INNER JOIN doctor_sessions s ON s.id = a.session_id
    WHERE s.session_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Colombo')::date
      AND s.status <> 'CANCELLED'
      AND (
            (s.start_time = TIME '07:00' AND s.end_time = TIME '09:30')
         OR (s.start_time = TIME '12:00' AND s.end_time = TIME '14:30')
      )
      AND a.appointment_number > 30;

    IF invalid_rows > 0 THEN
        RAISE EXCEPTION
            'V27 cannot retime a future session with an appointment number above 30';
    END IF;

    WITH proposed AS (
        SELECT
            id,
            doctor_id,
            room_id,
            session_date,
            CASE WHEN start_time = TIME '12:00'
                 THEN TIME '13:00' ELSE start_time END AS new_start,
            CASE WHEN start_time = TIME '07:00'
                 THEN TIME '11:00'
                 WHEN start_time = TIME '12:00'
                 THEN TIME '15:00' ELSE end_time END AS new_end
        FROM doctor_sessions
        WHERE session_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Colombo')::date
          AND status <> 'CANCELLED'
          AND (
                (start_time = TIME '07:00' AND end_time = TIME '09:30')
             OR (start_time = TIME '12:00' AND end_time = TIME '14:30')
          )
    )
    SELECT COUNT(*) INTO invalid_rows
    FROM proposed p
    INNER JOIN doctor_sessions other
      ON other.id <> p.id
     AND other.status <> 'CANCELLED'
     AND other.session_date = p.session_date
     AND (
            other.doctor_id = p.doctor_id
         OR (p.room_id IS NOT NULL AND other.room_id = p.room_id)
     )
     AND (other.session_date + other.start_time) < (p.session_date + p.new_end)
     AND (other.session_date + other.end_time) > (p.session_date + p.new_start);

    IF invalid_rows > 0 THEN
        RAISE EXCEPTION
            'V27 cannot retime future sessions without a doctor or room overlap';
    END IF;
END $$;

UPDATE doctor_sessions
SET start_time = CASE WHEN start_time = TIME '12:00'
                      THEN TIME '13:00' ELSE start_time END,
    end_time = CASE WHEN start_time = TIME '07:00'
                    THEN TIME '11:00'
                    WHEN start_time = TIME '12:00'
                    THEN TIME '15:00' ELSE end_time END,
    delay_minutes = 0
WHERE session_date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Colombo')::date
  AND status <> 'CANCELLED'
  AND (
        (start_time = TIME '07:00' AND end_time = TIME '09:30')
     OR (start_time = TIME '12:00' AND end_time = TIME '14:30')
  );

-- =============================================
-- END OF V27
-- =============================================
