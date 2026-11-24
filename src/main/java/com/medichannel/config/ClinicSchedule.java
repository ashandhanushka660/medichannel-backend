package com.medichannel.config;

import java.time.LocalTime;

import com.medichannel.entity.DoctorSession;
import com.medichannel.enums.ConsultationType;

/**
 * The hospital's published clinic day. This is the single source of truth
 * shared by the guest doctor directory and patient booking, so the sessions a
 * patient can book always match the availability shown before sign-in.
 */
public final class ClinicSchedule {

    public static final LocalTime START_TIME = LocalTime.of(7, 0);
    public static final LocalTime END_TIME = LocalTime.of(16, 0);
    public static final int HOURS = 8;
    public static final int MIN_SLOT_MINUTES = 5;
    public static final int MAX_SLOT_MINUTES = 30;
    public static final int MINUTES_PER_PATIENT = MIN_SLOT_MINUTES;
    public static final int MAX_APPOINTMENTS_PER_DAY = 60;

    // BR-06: physical booking closes 60 minutes before the session starts,
    // online booking closes 30 minutes before the session starts.
    public static final int PHYSICAL_BOOKING_CLOSE_MINUTES = 60;
    public static final int ONLINE_BOOKING_CLOSE_MINUTES = 30;

    private ClinicSchedule() {
    }

    public static int bookingCloseMinutes(ConsultationType consultationType) {
        return consultationType == ConsultationType.PHYSICAL
                ? PHYSICAL_BOOKING_CLOSE_MINUTES
                : ONLINE_BOOKING_CLOSE_MINUTES;
    }

    /**
     * Latest session start that is still bookable at the given wall-clock
     * time. A rolled-over cutoff means no session later today can be booked.
     */
    public static LocalTime bookingCutoff(
            LocalTime now,
            ConsultationType consultationType
    ) {
        LocalTime cutoff = now.plusMinutes(
                bookingCloseMinutes(consultationType)
        );
        return cutoff.isBefore(now) ? LocalTime.MAX : cutoff;
    }

    /**
     * A session is congruent with the published doctor availability only when
     * it fits inside the clinic day, uses a 5–30 minute patient slot, and never
     * publishes more than 60 places.
     */
    public static boolean isBookableSession(DoctorSession session) {
        return session != null
                && session.getStartTime() != null
                && session.getEndTime() != null
                && !session.getStartTime().isBefore(START_TIME)
                && !session.getEndTime().isAfter(END_TIME)
                && session.getMinutesPerPatient() >= MIN_SLOT_MINUTES
                && session.getMinutesPerPatient() <= MAX_SLOT_MINUTES
                && session.getMaxAppointments() >= 1
                && session.getMaxAppointments() <= MAX_APPOINTMENTS_PER_DAY;
    }
}
