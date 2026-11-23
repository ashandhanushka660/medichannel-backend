package com.medichannel.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Append-only audit event.
 *
 * Actors are identified by {@code user_id}; plaintext email addresses are
 * never stored here. Database triggers installed by V19 reject any update or
 * deletion of an audit row.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "appointment_id", updatable = false)
    private UUID appointmentId;

    @Column(name = "user_role", length = 50, updatable = false)
    private String userRole;

    @Column(name = "action", nullable = false, length = 255, updatable = false)
    private String action;

    @Column(name = "details", columnDefinition = "TEXT", updatable = false)
    private String details;

    @Column(name = "ip_address", length = 100, updatable = false)
    private String ipAddress;

    public AuditLog() {
    }

    public AuditLog(
            UUID userId,
            String userRole,
            String action,
            String details,
            String ipAddress
    ) {
        this(userId, userRole, action, details, ipAddress, null);
    }

    public AuditLog(
            UUID userId,
            String userRole,
            String action,
            String details,
            String ipAddress,
            UUID appointmentId
    ) {
        this.userId = userId;
        this.userRole = userRole;
        this.action = action;
        this.details = details;
        this.ipAddress = ipAddress;
        this.appointmentId = appointmentId;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (ipAddress == null || ipAddress.isBlank()) {
            ipAddress = "unknown";
        }
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAppointmentId() {
        return appointmentId;
    }

    public String getUserRole() {
        return userRole;
    }

    public String getAction() {
        return action;
    }

    public String getDetails() {
        return details;
    }

    public String getIpAddress() {
        return ipAddress;
    }
}
