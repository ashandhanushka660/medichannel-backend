package com.medichannel.config;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.entity.User;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/medichannel_test",
    "spring.datasource.username=postgres",
    "spring.datasource.password=local_test_only_password",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.show-sql=false",
    "spring.jpa.open-in-view=false",
    "APP_RESET_ALL_PASSWORDS=true",
    "APP_DEMO_PASSWORD=Password@123"
})
@Transactional
class AllUserPasswordRepairRunnerTests {

    @Autowired
    private AllUserPasswordRepairRunner runner;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void itRepairsStaleLockedAccountsForTheConfiguredPassword() {
        String email = "stale." + UUID.randomUUID() + "@medichannel.demo";
        User user = new User(
                roleRepository.findByName("DOCTOR").orElseThrow(),
                email,
                "stale-hash"
        );
        user.recordFailedLoginAttempt(5, Instant.now().plusSeconds(600));
        user.requirePasswordChange();
        userRepository.saveAndFlush(user);

        runner.run(null);

        User repaired = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("Password@123", repaired.getPasswordHash()))
                .isTrue();
        assertThat(repaired.getAccountStatus()).isEqualTo("ACTIVE");
        assertThat(repaired.isEmailVerified()).isTrue();
        assertThat(repaired.getFailedLoginAttempts()).isZero();
        assertThat(repaired.getLockedUntil()).isNull();
        assertThat(repaired.isPasswordChangeRequired()).isFalse();
    }
}
