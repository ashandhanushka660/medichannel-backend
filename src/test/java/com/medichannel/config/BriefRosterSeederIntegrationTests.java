package com.medichannel.config;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.SpecialisationRepository;

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
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "APP_BRIEF_ROSTER_SEED=false",
    "APP_BRIEF_ROSTER_WEEKS=2",
    "APP_DEMO_PASSWORD=DemoPass1!"
})
@Import(BriefRosterSeederIntegrationTests.FixedClockConfig.class)
@Transactional
class BriefRosterSeederIntegrationTests {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(
                    Instant.parse("2026-11-25T05:45:00Z"),
                    ZoneId.of("Asia/Colombo")
            );
        }
    }

    @Autowired
    private BriefRosterSeeder seeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private SpecialisationRepository specialisationRepository;

    @Test
    void itCreatesAWeeklyRosterAboveTheBriefMinimumAndIsIdempotent() {
        // The bean is disabled during context startup so other tests are not
        // affected; enable this instance only for the explicit call below.
        ReflectionTestUtils.setField(seeder, "enabled", true);

        Set<UUID> beforeIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT id FROM doctor_sessions",
                        UUID.class)
        );

        seeder.run(null);

        Set<UUID> createdIds = new HashSet<>(
                jdbcTemplate.queryForList("SELECT id FROM doctor_sessions",
                        UUID.class)
        );
        createdIds.removeAll(beforeIds);

        assertThat(createdIds).hasSizeGreaterThan(120);
        assertThat(specialisationRepository.findAll())
                .hasSizeGreaterThanOrEqualTo(15);
        assertThat(doctorRepository.findPublicDirectory())
                .hasSizeGreaterThanOrEqualTo(40);

        long currentWeek = count(
                """
                SELECT COUNT(*)
                FROM doctor_sessions
                WHERE session_date BETWEEN ? AND ?
                  AND status <> 'CANCELLED'
                """,
                Date.valueOf(LocalDate.of(2026, 11, 23)),
                Date.valueOf(LocalDate.of(2026, 11, 29))
        );
        assertThat(currentWeek).isGreaterThan(120L);

        // Only the sessions this runner created are asserted here. Legacy
        // test sessions may use older windows and are deliberately untouched.
        for (UUID id : createdIds) {
            String shape = jdbcTemplate.queryForObject(
                    """
                    SELECT start_time::text || '|' || end_time::text
                           || '|' || max_appointments
                           || '|' || minutes_per_patient
                    FROM doctor_sessions
                    WHERE id = ?
                    """,
                    String.class,
                    id
            );
            assertThat(shape).isIn(
                    "07:00:00|09:30:00|30|5",
                    "12:00:00|14:30:00|30|5"
            );
        }

        seeder.run(null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM doctor_sessions",
                Long.class
        )).isEqualTo((long) beforeIds.size() + createdIds.size());
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }
}
