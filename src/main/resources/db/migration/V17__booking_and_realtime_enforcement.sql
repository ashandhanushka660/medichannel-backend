-- =============================================
-- MEDICHANNEL
-- V17: Booking and real-time enforcement
-- Target: PostgreSQL
-- =============================================

-- 1. RT-04: the CALLED notification is distinct from
-- CONSULTATION_STARTED. Recreate the V8 check constraint so the new
-- enum value can be persisted.
ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notification_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notification_type
    CHECK (
        type IN (
            'APPOINTMENT_BOOKED',
            'APPOINTMENT_ARRIVED',
            'APPOINTMENT_CANCELLED',
            'APPOINTMENT_REMINDER',
            'PAYMENT_RECEIVED',
            'CALLED',
            'CONSULTATION_STARTED',
            'CONSULTATION_COMPLETED',
            'SUMMARY_AVAILABLE',
            'NEW_CHAT_MESSAGE',
            'REVIEW_RECEIVED',
            'ACCOUNT_STATUS_CHANGED'
        )
    );


-- 2. BR-07: enforce the patient cancellation rule at the database
-- boundary as well as in AppointmentService.
--
-- A cancellation is either:
--   * patient initiated - the old row must be a BOOKED, PENDING booking
--     and a cancellation reason must be supplied; or
--   * staff/system initiated - the application marks the reason with
--     STAFF: or SYSTEM: so legitimate session cancellations, doctor
--     declines and manager cancellations of paid appointments still
--     work. A direct SQL UPDATE that does not use those application
--     markers is rejected.
CREATE OR REPLACE FUNCTION enforce_booking_cancellation_rules()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'UPDATE' THEN
        RETURN NEW;
    END IF;

    -- Only a fresh transition into CANCELLED is checked.
    IF NEW.status IS DISTINCT FROM 'CANCELLED'
       OR OLD.status = 'CANCELLED' THEN
        RETURN NEW;
    END IF;

    IF NEW.cancellation_reason_encrypted IS NULL
       OR btrim(NEW.cancellation_reason_encrypted) = '' THEN
        RAISE EXCEPTION 'Cancellation reason is required.'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.cancellation_reason_encrypted LIKE 'STAFF:%'
       OR NEW.cancellation_reason_encrypted LIKE 'SYSTEM:%' THEN
        RETURN NEW;
    END IF;

    IF OLD.status <> 'BOOKED' THEN
        RAISE EXCEPTION
            'Only booked appointments can be cancelled by patients.'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.payment_status <> 'PENDING' THEN
        RAISE EXCEPTION
            'Paid appointments cannot be cancelled by patients.'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_enforce_booking_cancellation_rules
    ON appointments;

CREATE TRIGGER trg_enforce_booking_cancellation_rules
BEFORE UPDATE OF status, cancellation_reason_encrypted
ON appointments
FOR EACH ROW
EXECUTE FUNCTION enforce_booking_cancellation_rules();


-- 3. RT-02/BR-03: capacity is based on live queue occupants, while the
-- appointment number counter keeps increasing so cancelled/no-show
-- numbers are never reused. The original V4 counter and number checks
-- cap both at max_appointments; those caps would make a freed slot
-- impossible to rebook, so they are relaxed here.
ALTER TABLE doctor_sessions
    DROP CONSTRAINT IF EXISTS chk_session_appointment_counter;

ALTER TABLE doctor_sessions
    ADD CONSTRAINT chk_session_appointment_counter
    CHECK (last_appointment_number >= 0);

ALTER TABLE appointments
    DROP CONSTRAINT IF EXISTS chk_appointment_number;

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_number
    CHECK (appointment_number > 0);

CREATE OR REPLACE FUNCTION assign_appointment_number()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    session_capacity INTEGER;
    active_count INTEGER;
    allocated_number INTEGER;
BEGIN
    -- Lock the session row first so concurrent bookings for the same
    -- session are serialized before capacity is evaluated.
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
       AND status IN ('BOOKED', 'ARRIVED', 'IN_CONSULTATION');

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

CREATE INDEX IF NOT EXISTS idx_appointments_session_active_status
    ON appointments(session_id, appointment_number)
    WHERE status IN ('BOOKED', 'ARRIVED', 'IN_CONSULTATION');

-- =============================================
-- END OF V17
-- =============================================
