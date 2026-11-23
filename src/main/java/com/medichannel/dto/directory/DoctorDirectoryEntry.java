package com.medichannel.dto.directory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.SessionStatus;

/**
 * Public, non-identifying information a visitor needs before signing in.
 * It contains doctor directory details plus the live queue of the doctor's
 * current session. No patient details are ever included.
 */
public record DoctorDirectoryEntry(

        UUID doctorId,
        String doctorName,
        String firstName,
        String lastName,

        UUID specialisationId,
        String specialisation,
        BigDecimal consultationFee,
        boolean available,

        String roomNumber,
        String roomName,

        // The hospital's default eight-hour clinic day. A session may
        // override these times for a particular date.
        LocalTime defaultStartTime,
        LocalTime defaultEndTime,
        int defaultHours,

        UUID sessionId,
        LocalDate sessionDate,
        LocalTime startTime,
        LocalTime endTime,
        ConsultationType consultationType,
        SessionStatus sessionStatus,

        Integer nowServing,
        Integer nextNumber,
        int remainingAppointments,
        int maxAppointments

) {
}
