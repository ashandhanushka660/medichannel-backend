package com.medichannel.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.entity.User;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.DataProtectionService;

/**
 * One-off, idempotent backfill for the email-at-rest migration.
 *
 * V18 adds the lookup hash, force-change flag and a plaintext backup column.
 * This runner encrypts legacy users.email values in place and derives the
 * lookup hash while the original value is still readable. The backup column
 * is deliberately left in place: the separate V20 migration must only be
 * applied after the backfill has been verified in the target database.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserEmailEncryptionMigrationRunner implements ApplicationRunner {

    private static final Logger logger =
            LoggerFactory.getLogger(UserEmailEncryptionMigrationRunner.class);

    private final UserRepository userRepository;
    private final DataProtectionService dataProtectionService;
    private final JdbcTemplate jdbcTemplate;

    public UserEmailEncryptionMigrationRunner(
            UserRepository userRepository,
            DataProtectionService dataProtectionService,
            JdbcTemplate jdbcTemplate
    ) {
        this.userRepository = userRepository;
        this.dataProtectionService = dataProtectionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<User> changed = new ArrayList<>();
        int encrypted = 0;
        int hashed = 0;

        for (User user : userRepository.findAll()) {
            String storedEmail = user.getStoredEmail();

            if (storedEmail == null || storedEmail.isBlank()) {
                continue;
            }

            boolean alreadyEncrypted =
                    DataProtectionService.isEncrypted(storedEmail);

            String plaintext = alreadyEncrypted
                    ? dataProtectionService.decryptEmail(storedEmail)
                    : storedEmail;

            String expectedHash = dataProtectionService.emailLookupHash(plaintext);

            if (!alreadyEncrypted) {
                // Rollback safety net until V20 is explicitly applied.
                jdbcTemplate.update("""
                        UPDATE users
                        SET email_plaintext_backup = ?
                        WHERE id = ?
                          AND email_plaintext_backup IS NULL
                        """, plaintext, user.getId());

                user.changeEmail(plaintext);
                encrypted++;
                changed.add(user);
            } else if (!expectedHash.equals(user.getEmailLookupHash())) {
                user.setEmailLookupHashForMigration(expectedHash);
                hashed++;
                changed.add(user);
            }
        }

        if (!changed.isEmpty()) {
            userRepository.saveAllAndFlush(changed);
        }

        Long remainingPlaintext = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email NOT LIKE 'v1:%'",
                Long.class
        );

        logger.info(
                "Email protection backfill: {} encrypted, {} lookup hashes "
                        + "repaired, {} plaintext rows remaining.",
                encrypted,
                hashed,
                remainingPlaintext == null ? 0L : remainingPlaintext
        );
    }
}
