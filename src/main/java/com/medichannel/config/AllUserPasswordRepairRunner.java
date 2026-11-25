package com.medichannel.config;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.medichannel.entity.User;
import com.medichannel.repository.UserRepository;

@Component
@ConditionalOnProperty(name = "APP_RESET_ALL_PASSWORDS", havingValue = "true")
public class AllUserPasswordRepairRunner implements ApplicationRunner {
    private static final Logger logger =
            LoggerFactory.getLogger(AllUserPasswordRepairRunner.class);
    private static final int MAXIMUM_BCRYPT_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String password;

    public AllUserPasswordRepairRunner(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${APP_DEMO_PASSWORD:}") String password
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        validatePassword();

        int updated = 0;
        for (User user : userRepository.findAll()) {
            boolean passwordChanged = !passwordEncoder.matches(
                    password,
                    user.getPasswordHash()
            );
            boolean needsUnlock = "LOCKED".equals(user.getAccountStatus())
                    || user.isLockedAt(clock.instant());
            boolean needsActivation = !"DELETED".equals(user.getAccountStatus())
                    && (!"ACTIVE".equals(user.getAccountStatus())
                            || !user.isEmailVerified());

            if (!passwordChanged
                    && !needsUnlock
                    && !needsActivation
                    && !user.isPasswordChangeRequired()) {
                continue;
            }

            if (passwordChanged) {
                user.updatePasswordHash(passwordEncoder.encode(password));
            }

            if ("DELETED".equals(user.getAccountStatus())) {
                user.unlock();
            } else {
                user.activateByAdministrator();
            }

            user.clearPasswordChangeRequired();
            user.incrementTokenVersion();
            userRepository.saveAndFlush(user);
            updated += 1;
        }

        logger.info("Password repair updated {} user accounts.", updated);
    }

    private void validatePassword() {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "APP_RESET_ALL_PASSWORDS is enabled but APP_DEMO_PASSWORD is not set."
            );
        }

        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes > MAXIMUM_BCRYPT_PASSWORD_BYTES) {
            throw new IllegalStateException(
                    "APP_DEMO_PASSWORD must not exceed 72 UTF-8 bytes."
            );
        }
    }
}
