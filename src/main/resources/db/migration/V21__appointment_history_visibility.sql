-- ============================================================================
-- MEDICHANNEL
-- V21: Participant-scoped appointment history visibility
-- ============================================================================
-- Clearing an appointment from a history view must never remove the clinical,
-- payment or audit record. These flags are per participant so a patient
-- clearing their view does not affect the assigned doctor's view (or vice
-- versa). Active and future appointments can never be hidden.

ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS patient_hidden_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS doctor_hidden_at  TIMESTAMPTZ;

ALTER TABLE appointments
    DROP CONSTRAINT IF EXISTS chk_appointment_hidden_terminal;

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_hidden_terminal CHECK (
        (
            patient_hidden_at IS NULL
            OR status IN ('COMPLETED', 'NO_SHOW', 'CANCELLED')
        )
        AND
        (
            doctor_hidden_at IS NULL
            OR status IN ('COMPLETED', 'NO_SHOW', 'CANCELLED')
        )
    );

CREATE INDEX IF NOT EXISTS idx_appointments_patient_visible
    ON appointments(patient_id, created_at DESC)
    WHERE patient_hidden_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_appointments_session_visible_number
    ON appointments(session_id, appointment_number)
    WHERE doctor_hidden_at IS NULL;
