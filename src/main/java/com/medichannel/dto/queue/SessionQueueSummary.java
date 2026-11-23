package com.medichannel.dto.queue;

import java.time.Instant;
import java.util.UUID;

import com.medichannel.enums.SessionStatus;

/**
 * Non-identifying queue state that is safe to broadcast to every
 * participant of a session. It never contains patient names or
 * appointment identifiers.
 */
public record SessionQueueSummary(

        UUID sessionId,

        Integer nowServing,
        Integer nextNumber,

        int remainingAppointments,

        // Capacity that is still allocatable. Unlike remainingAppointments,
        // this never reuses the number of a cancelled/no-show appointment.
        int availableSlots,

        int maxAppointments,

        SessionStatus status,

        Instant updatedAt

) {
}
