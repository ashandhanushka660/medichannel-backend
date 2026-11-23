package com.medichannel.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import com.medichannel.security.DataProtectionService;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    // Ciphertext envelope ("v1:...") after the startup backfill. Plaintext is
    // only ever held transiently before the persist/update callback runs.
    @Column(name = "email", nullable = false, length = 512)
    private String email;

    // HMAC-SHA256 of the normalised plaintext email. Used for every equality
    // lookup and for database-level uniqueness.
    @Column(name = "email_lookup_hash", length = 64)
    private String emailLookupHash;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired = false;

    @Column(name = "account_status", nullable = false, length = 20)
    private String accountStatus = "PENDING";

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 1;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(Role role, String email, String passwordHash) {
        this.role = role;
        this.email = email;
        this.passwordHash = passwordHash;
        this.emailLookupHash = computeEmailLookupHash(email);
    }

    @PrePersist
    protected void onCreate() {
        protectEmail();
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        protectEmail();
        updatedAt = Instant.now();
    }

    /**
     * Encrypt the email before it reaches the database. Already-encrypted
     * values are left untouched so repeated updates never nest envelopes.
     */
    private void protectEmail() {
        if (email == null || !DataProtectionService.isAvailable()) {
            return;
        }

        DataProtectionService protection = DataProtectionService.getInstance();

        if (DataProtectionService.isEncrypted(email)) {
            if (emailLookupHash == null) {
                emailLookupHash = protection.emailLookupHash(
                        protection.decryptEmail(email)
                );
            }
            return;
        }

        emailLookupHash = protection.emailLookupHash(email);
        email = protection.encryptEmail(email);
    }

    private static String computeEmailLookupHash(String email) {
        if (email == null || !DataProtectionService.isAvailable()) {
            return null;
        }
        return DataProtectionService.getInstance().emailLookupHash(email);
    }

    // Record an unsuccessful login attempt.
    public void recordFailedLoginAttempt(
            int maximumAttempts,
            Instant lockUntil
    ) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException(
                    "Maximum attempts must be positive."
            );
        }

        failedLoginAttempts++;

        if (failedLoginAttempts >= maximumAttempts) {
            if (lockUntil == null || !lockUntil.isAfter(Instant.now())) {
                throw new IllegalArgumentException(
                        "Lock expiry must be in the future."
                );
            }

            this.lockedUntil = lockUntil;
            this.accountStatus = "LOCKED";
        }
    }

    // Check whether the account is currently locked.
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    // Clear an expired temporary lock without changing
    // independently managed account restrictions.
    public void clearExpiredLock(Instant now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) {
            lockedUntil = null;
            failedLoginAttempts = 0;

            if ("LOCKED".equals(accountStatus)) {
                accountStatus = emailVerified ? "ACTIVE" : "PENDING";
            }
        }
    }

    // Record a successful login and reset failure tracking.
    public void recordSuccessfulLogin(Instant now) {
        if (!"ACTIVE".equals(accountStatus) || !emailVerified) {
            throw new IllegalStateException(
                    "Only active, verified accounts can log in."
            );
        }

        failedLoginAttempts = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    // Activate an account after the verification process.
    public void activateAfterVerification() {
        if ("LOCKED".equals(accountStatus)
                || "SUSPENDED".equals(accountStatus)
                || "DISABLED".equals(accountStatus)) {
            throw new IllegalStateException(
                    "This account cannot be activated."
            );
        }

        this.emailVerified = true;
        this.accountStatus = "ACTIVE";
    }

    // Administrator approval. This also clears any temporary lock.
    public void activateByAdministrator() {
        this.emailVerified = true;
        this.accountStatus = "ACTIVE";
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    public void suspend() {
        this.accountStatus = "SUSPENDED";
        this.lockedUntil = null;
    }

    public void disable() {
        this.accountStatus = "DISABLED";
        this.lockedUntil = null;
    }

    // Soft delete. Records with history are never removed (BR-11), so the
    // account is disabled permanently and every issued token is revoked.
    public void softDelete() {
        this.accountStatus = "DELETED";
        this.lockedUntil = null;
        this.failedLoginAttempts = 0;
        incrementTokenVersion();
    }

    public void changeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        this.email = email;
        this.emailLookupHash = computeEmailLookupHash(email);
    }

    /**
     * Raw database value. Intentionally distinct from {@link #getEmail()},
     * which always returns the readable plaintext. Used by the one-off
     * backfill runner to identify legacy rows.
     */
    public String getStoredEmail() {
        return email;
    }

    public boolean isEmailEncrypted() {
        return DataProtectionService.isEncrypted(email);
    }

    public String getEmailLookupHash() {
        return emailLookupHash;
    }

    /** Used by the startup backfill to repair a missing lookup hash in place. */
    public void setEmailLookupHashForMigration(String emailLookupHash) {
        this.emailLookupHash = emailLookupHash;
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public void requirePasswordChange() {
        this.passwordChangeRequired = true;
    }

    public void clearPasswordChangeRequired() {
        this.passwordChangeRequired = false;
    }

    // Remove a temporary lock without granting a new verification status.
    public void unlock() {
        if (!"LOCKED".equals(accountStatus)) {
            return;
        }

        this.lockedUntil = null;
        this.failedLoginAttempts = 0;
        this.accountStatus = emailVerified ? "ACTIVE" : "PENDING";
    }

    public UUID getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public String getEmail() {
        if (DataProtectionService.isAvailable()
                && DataProtectionService.isEncrypted(email)) {
            return DataProtectionService.getInstance().decryptEmail(email);
        }
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public void incrementTokenVersion() {
        this.tokenVersion++;
    }

    public void updatePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("Password hash is required.");
        }
        this.passwordHash = passwordHash;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
