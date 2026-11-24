package com.medichannel.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.dto.report.ReportOverviewResponse;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Role;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.SessionStatus;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.SpecialisationRepository;
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
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
})
@Import(ReportServiceIntegrationTests.FixedClockConfig.class)
@Transactional
class ReportServiceIntegrationTests {

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
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpecialisationRepository specialisationRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private DoctorSessionRepository doctorSessionRepository;

    @Autowired
    private ReportService reportService;

    @Test
    void weeklyCountUsesTheColomboWeekAndExcludesCancelledSessions() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Report Week " + unique,
                        "Temporary weekly report test"
                )
        );
        Doctor doctor = createDoctor(doctorRole, specialisation, unique);

        // Previous week - must not be counted.
        openSession(doctor, LocalDate.of(2026, 11, 22));
        // Monday of the current Asia/Colombo week - counted.
        openSession(doctor, LocalDate.of(2026, 11, 23));
        // Cancelled session in the current week - not counted.
        cancelledSession(doctor, LocalDate.of(2026, 11, 25));
        // Sunday of the current week - counted.
        openSession(doctor, LocalDate.of(2026, 11, 29));
        // Next week - must not be counted.
        openSession(doctor, LocalDate.of(2026, 11, 30));

        ReportOverviewResponse report = reportService.overview(
                LocalDate.of(2026, 11, 25),
                LocalDate.of(2026, 11, 25)
        );

        assertThat(report.sessionsWeekFrom())
                .isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(report.sessionsWeekTo())
                .isEqualTo(LocalDate.of(2026, 11, 29));
        assertThat(report.sessionsThisWeek()).isEqualTo(2L);
    }

    private Doctor createDoctor(
            Role role,
            Specialisation specialisation,
            String unique
    ) {
        User user = userRepository.saveAndFlush(new User(
                role,
                "report.week." + unique.toLowerCase() + "@example.test",
                "test-password-hash"
        ));
        user.activateAfterVerification();
        userRepository.saveAndFlush(user);

        return doctorRepository.saveAndFlush(new Doctor(
                user,
                specialisation,
                "Report",
                "Doctor",
                "REPORT-" + unique,
                "MBBS",
                "Temporary weekly report test",
                new BigDecimal("1500.00")
        ));
    }

    private DoctorSession openSession(Doctor doctor, LocalDate date) {
        return saveSession(doctor, date, false);
    }

    private DoctorSession cancelledSession(Doctor doctor, LocalDate date) {
        return saveSession(doctor, date, true);
    }

    private DoctorSession saveSession(
            Doctor doctor,
            LocalDate date,
            boolean cancelled
    ) {
        DoctorSession session = new DoctorSession(
                doctor,
                null,
                date,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ConsultationType.ONLINE,
                ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION
        );
        session.updateSchedulingDetails(
                ClinicSchedule.MINUTES_PER_PATIENT,
                new BigDecimal("1500.00")
        );

        if (cancelled) {
            session.cancel();
        } else {
            session.open();
        }

        DoctorSession saved = doctorSessionRepository.saveAndFlush(session);
        assertThat(saved.getStatus()).isEqualTo(cancelled
                ? SessionStatus.CANCELLED
                : SessionStatus.OPEN);
        return saved;
    }
}
