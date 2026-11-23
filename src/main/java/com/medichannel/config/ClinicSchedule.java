package com.medichannel.config;

import java.time.LocalTime;

import com.medichannel.entity.DoctorSession;

/**
 * The hospital's published clinic day. This is the single source of truth
 * shared by the guest doctor directory and patient booking, so the sessions a
 * patient can book always match the availability shown before sign-in.
 */
public final class ClinicSchedule {

    public static final LocalTime START_TIME = LocalTime.of(7, 0);
    public static final LocalTime END_TIME = LocalTime.of(16, 0);
    public static final int HOURS = 8;
    public static final int MINUTES_PER_PATIENT = 10;

    private ClinicSchedule() {
    }

    /**
     * A session is congruent with the published doctor availability only when
     * it fits entirely inside the clinic day and allocates one 10-minute slot
     * per patient.
     */
    public static boolean isBookableSession(DoctorSession session) {
        return session != null
                && session.getStartTime() != null
                && session.getEndTime() != null
                && !session.getStartTime().isBefore(START_TIME)
                && !session.getEndTime().isAfter(END_TIME)
                && session.getMinutesPerPatient() == MINUTES_PER_PATIENT;
    }
}
