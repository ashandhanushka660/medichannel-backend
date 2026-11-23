package com.medichannel.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Application-level protection for user email addresses.
 *
 * The ciphertext format is the same AES-256-GCM "v1:" envelope used by
 * {@link PatientDataProtectionService}. Discriminator lookups use an HMAC of
 * the normalised address so no plaintext email is ever indexed or compared.
 *
 * A static reference is published so JPA lifecycle callbacks and Spring Data
 * default repository methods (which are not Spring beans themselves) can use
 * the same protection rules. When no application context is present - for
 * example in plain unit tests - callers fall back to handling the raw value.
 */
@Service
public class DataProtectionService {

    private static final String ENCRYPTION_PREFIX = "v1:";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static volatile DataProtectionService instance;

    private final PatientDataProtectionService patientDataProtectionService;
    private final SecretKey hmacKey;

    public DataProtectionService(
            PatientDataProtectionService patientDataProtectionService,
            @Value("${HMAC_SECRET}") String encodedHmacKey
    ) {
        this.patientDataProtectionService = patientDataProtectionService;
        this.hmacKey = createKey(encodedHmacKey, HMAC_ALGORITHM);
        instance = this;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public static DataProtectionService getInstance() {
        DataProtectionService current = instance;
        if (current == null) {
            throw new IllegalStateException(
                    "Email protection is not initialised."
            );
        }
        return current;
    }

    /** Shared prefix check used by the entity and the backfill runner. */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTION_PREFIX);
    }

    public String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Encrypt a normalised email. The result is safe to store in the
     * users.email column.
     */
    public String encryptEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException("Email is required.");
        }
        return patientDataProtectionService.encrypt(normalized);
    }

    public String decryptEmail(String encryptedEmail) {
        if (!isEncrypted(encryptedEmail)) {
            return encryptedEmail;
        }
        return patientDataProtectionService.decrypt(encryptedEmail);
    }

    /**
     * Deterministic HMAC-SHA256 lookup value for the normalised email. The
     * encoding matches the existing NIC lookup-hash convention.
     */
    public String emailLookupHash(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);

            byte[] hash = mac.doFinal(
                    normalized.getBytes(StandardCharsets.UTF_8)
            );

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(hash);

        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to process email address."
            );
        }
    }

    /** Convenience used by repository default methods. */
    public static String lookupHashFor(String email) {
        return getInstance().emailLookupHash(email);
    }

    private SecretKey createKey(String encodedKey, String algorithm) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encodedKey);

            if (keyBytes.length != 32) {
                throw new IllegalStateException(
                        "Configured security keys must contain exactly 32 bytes."
                );
            }

            return new SecretKeySpec(keyBytes, algorithm);

        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Configured security key must be valid Base64.",
                    exception
            );
        }
    }
}
