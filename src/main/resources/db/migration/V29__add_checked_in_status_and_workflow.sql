ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS checked_in_at TIMESTAMPTZ;

UPDATE appointments
SET checked_in_at = arrived_at
WHERE checked_in_at IS NULL
  AND arrived_at IS NOT NULL;

ALTER TABLE appointments
    DROP CONSTRAINT IF EXISTS chk_appointment_status;

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_status
    CHECK (
        status IN (
            'BOOKED',
            'CHECKED_IN',
            'ARRIVED',
            'IN_CONSULTATION',
            'COMPLETED',
            'NO_SHOW',
            'CANCELLED'
        )
    );

ALTER TABLE appointments
    DROP CONSTRAINT IF EXISTS chk_appointment_acceptance_status;

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_acceptance_status
    CHECK (acceptance_status IN ('PENDING', 'ACCEPTED', 'DECLINED'));

ALTER TABLE appointments
    DROP CONSTRAINT IF EXISTS chk_appointment_checked_in_at;

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_checked_in_at
    CHECK (status <> 'BOOKED' OR checked_in_at IS NULL);

DROP INDEX IF EXISTS uq_active_appointment_patient_session;

CREATE UNIQUE INDEX uq_active_appointment_patient_session
    ON appointments(session_id, patient_id)
    WHERE status IN (
        'BOOKED',
        'CHECKED_IN',
        'ARRIVED',
        'IN_CONSULTATION'
    );

DROP INDEX IF EXISTS idx_appointments_session_active_status;

CREATE INDEX idx_appointments_session_active_status
    ON appointments(session_id, appointment_number)
    WHERE status IN (
        'BOOKED',
        'CHECKED_IN',
        'ARRIVED',
        'IN_CONSULTATION'
    );

CREATE OR REPLACE FUNCTION assign_appointment_number()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    session_capacity INTEGER;
    active_count INTEGER;
    allocated_number INTEGER;
BEGIN
    SELECT max_appointments
      INTO session_capacity
      FROM doctor_sessions
     WHERE id = NEW.session_id
       AND status = 'OPEN'
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Session does not exist, is not open, or is fully booked'
            USING ERRCODE = '23514';
    END IF;

    SELECT COUNT(*)
      INTO active_count
      FROM appointments
     WHERE session_id = NEW.session_id
       AND status IN (
           'BOOKED',
           'CHECKED_IN',
           'ARRIVED',
           'IN_CONSULTATION'
       );

    IF active_count >= session_capacity THEN
        RAISE EXCEPTION
            'Session does not exist, is not open, or is fully booked'
            USING ERRCODE = '23514';
    END IF;

    UPDATE doctor_sessions
       SET last_appointment_number = last_appointment_number + 1
     WHERE id = NEW.session_id
    RETURNING last_appointment_number
      INTO allocated_number;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Session does not exist, is not open, or is fully booked'
            USING ERRCODE = '23514';
    END IF;

    NEW.appointment_number := allocated_number;
    RETURN NEW;
END;
$$;
