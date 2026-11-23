package com.medichannel.dto.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit event view. Deliberately contains no email address or medical content.
 */
public record AuditLogResponse(
        UUID id,
        Instant createdAt,
        UUID userId,
        String userRole,
        UUID appointmentId,
        String action,
        String details,
        String ipAddress
) {
}
