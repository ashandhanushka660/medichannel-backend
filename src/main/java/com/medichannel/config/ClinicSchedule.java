package com.medichannel.config;

import java.time.LocalTime;

import com.medichannel.entity.DoctorSession;
import com.medichannel.enums.ConsultationType;

/**
 * The hospital's published consultation schedule. This is the single source
 * of truth shared by session creation, patient booking and the public doctor
 * directory: only the two daily windows below are bookable.
 *
 * The 07:00-16:00 envelope remains as the hospital-day display value; the
 * lunch gap between the sessions is not itself bookable.
 */
public final class ClinicSchedule {

    public static final LocalTime MORNING_START = LocalTime.of(7, 0);
    public static final LocalTime MORNING_END = LocalTime.of(9, 30);
    public static final LocalTime AFTERNOON_START = LocalTime.of(12, 0);
    public static final LocalTime AFTERNOON_END = LocalTime.of(14, 30);

    // Hospital-day envelope shown in the public directory.
    public static final LocalTime START_TIME = MORNING_START;
    public static final LocalTime END_TIME = LocalTime.of(16, 0);
    public static final int HOURS = 8;

    public static final int MIN_SLOT_MINUTES = 5;
    public static final int MAX_SLOT_MINUTES = 5;
    public static final int MINUTES_PER_PATIENT = 5;
    public static final int MAX_APPOINTMENTS_PER_SESSION = 30;
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

    public static boolean isAllowedWindow(LocalTime start, LocalTime end) {
        return (MORNING_START.equals(start) && MORNING_END.equals(end))
                || (AFTERNOON_START.equals(start)
                        && AFTERNOON_END.equals(end));
    }

    public static String allowedWindowLabel() {
        return "07:00–09:30 or 12:00–14:30";
    }

    /**
     * End of the period filter value sent by the UI. The two published
     * sessions have their own ends; an unknown value keeps the old hourly
     * behaviour so bad input cannot widen the results.
     */
    public static LocalTime periodEnd(LocalTime period) {
        if (period == null) {
            return LocalTime.of(23, 59, 59);
        }
        if (MORNING_START.equals(period)) {
            return MORNING_END;
        }
        if (AFTERNOON_START.equals(period)) {
            return AFTERNOON_END;
        }
        return period.plusHours(1);
    }

    /**
     * A session is congruent with the published schedule only when it is one
     * of the two exact windows, uses a five-minute patient slot, and never
     * publishes more than 30 places. Two such sessions give the doctor a
     * 60-patient day.
     */
    public static boolean isBookableSession(DoctorSession session) {
        return session != null
                && isAllowedWindow(session.getStartTime(), session.getEndTime())
                && session.getMinutesPerPatient() == MINUTES_PER_PATIENT
                && session.getMaxAppointments() >= 1
                && session.getMaxAppointments() <= MAX_APPOINTMENTS_PER_SESSION;
    }
}
