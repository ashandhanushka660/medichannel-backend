-- =============================================
-- MEDICHANNEL
-- V22: Published clinic day, queue delays and cancellation reasons
-- =============================================

-- A delayed booking shifts every later booking in the same session.
ALTER TABLE appointments
    ADD COLUMN delay_minutes INTEGER NOT NULL DEFAULT 0;

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_delay
    CHECK (delay_minutes >= 0);

-- The roster is rewritten in one pass; the normal overlap guard would reject
-- intermediate rows while doctors still have several old time blocks.
ALTER TABLE doctor_sessions DISABLE TRIGGER trg_prevent_session_overlap;


-- Normalise the published roster. Each available doctor keeps one session
-- per day, and that session covers the advertised 07:00-16:00 clinic day
-- with 10-minute patient slots.
CREATE TEMP TABLE clinic_session_keep AS
SELECT id, doctor_id
FROM (
    SELECT
        s.id,
        s.doctor_id,
        ROW_NUMBER() OVER (
            PARTITION BY s.doctor_id, s.session_date
            ORDER BY
                CASE WHEN s.start_time = TIME '07:00'
                      AND s.end_time = TIME '16:00' THEN 0 ELSE 1 END,
                s.start_time,
                s.created_at,
                s.id
        ) AS position
    FROM doctor_sessions s
    WHERE s.session_date >= CURRENT_DATE
      AND s.status <> 'CANCELLED'
) ranked
WHERE position = 1;

UPDATE doctor_sessions s
SET status = 'CANCELLED'
WHERE s.session_date >= CURRENT_DATE
  AND s.status <> 'CANCELLED'
  AND NOT EXISTS (
      SELECT 1 FROM clinic_session_keep k WHERE k.id = s.id
  );

UPDATE doctor_sessions s
SET start_time = TIME '07:00',
    end_time = TIME '16:00',
    minutes_per_patient = 10,
    max_appointments = GREATEST(s.max_appointments, 48),
    delay_minutes = 0
FROM clinic_session_keep k
WHERE s.id = k.id;

-- Give each rostered physical doctor a distinct consultation room for the
-- day now that every session uses the same published window.
WITH physical_doctors AS (
    SELECT DISTINCT s.doctor_id
    FROM doctor_sessions s
    INNER JOIN clinic_session_keep k ON k.id = s.id
    WHERE s.consultation_type = 'PHYSICAL'
),
doctor_rank AS (
    SELECT doctor_id,
           ROW_NUMBER() OVER (ORDER BY doctor_id) AS position
    FROM physical_doctors
),
consultation_rooms AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY room_number) AS position
    FROM rooms
    WHERE room_type = 'CONSULTATION'
      AND is_active = TRUE
),
room_count AS (
    SELECT COUNT(*) AS total FROM consultation_rooms
)
UPDATE doctor_sessions s
SET room_id = r.id
FROM doctor_rank d
CROSS JOIN room_count c
JOIN consultation_rooms r
  ON r.position = ((d.position - 1) % NULLIF(c.total, 0)) + 1
WHERE s.id IN (SELECT id FROM clinic_session_keep)
  AND s.doctor_id = d.doctor_id
  AND s.consultation_type = 'PHYSICAL';

DROP TABLE clinic_session_keep;

ALTER TABLE doctor_sessions ENABLE TRIGGER trg_prevent_session_overlap;
