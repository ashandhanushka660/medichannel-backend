package com.medichannel.controller;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.dto.appointment.AppointmentBookingRequest;
import com.medichannel.dto.appointment.WalkInBookingRequest;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationPeriod;
import com.medichannel.enums.ConsultationType;
import com.medichannel.exception.ConflictException;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;
import com.medichannel.service.AppointmentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 11:00-13:00 break is a no-number period. Neither patient self-booking
 * nor reception walk-in booking may allocate an appointment number then.
 */
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
@Import(BreakBookingIntegrationTests.FixedClockConfig.class)
@Transactional
class BreakBookingIntegrationTests {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedTestClock() {
            // 11:15 Asia/Colombo, inside the published break.
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
    private PatientRepository patientRepository;

    @Autowired
    private DoctorSessionRepository doctorSessionRepository;

    @Autowired
    private PatientDataProtectionService dataProtectionService;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void breakBlocksBothBookingPathsAndAllocatesNoNumber() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Role customerRole = roleRepository.findByName("CUSTOMER").orElseThrow();
        Role managerRole = roleRepository.findByName("MANAGER").orElseThrow();

        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Break Test " + unique,
                        "Temporary no-number break test"
                )
        );
        Doctor doctor = createDoctor(
                doctorRole, specialisation, unique
        );
        DoctorSession morning = openSession(doctor, unique);

        User patientUser = userRepository.saveAndFlush(new User(
                customerRole,
                "break.patient." + unique + "@example.test",
                "test-password-hash"
        ));
        patientUser.activateAfterVerification();
        userRepository.saveAndFlush(patientUser);
        patientRepository.saveAndFlush(new Patient(
                patientUser,
                dataProtectionService.encrypt("Break"),
                dataProtectionService.encrypt("Patient"),
                dataProtectionService.encrypt("0771234567"),
                null,
                dataProtectionService.encrypt("2000-01-01"),
                dataProtectionService.encrypt("OTHER")
        ));

        User manager = userRepository.saveAndFlush(new User(
                managerRole,
                "break.manager." + unique + "@example.test",
                "test-password-hash"
        ));
        manager.activateAfterVerification();
        userRepository.saveAndFlush(manager);

        AppointmentBookingRequest patientRequest =
                new AppointmentBookingRequest(
                        morning.getId(),
                        "Break period review",
                        ConsultationPeriod.MORNING
                );
        WalkInBookingRequest walkInRequest = new WalkInBookingRequest(
                morning.getId(),
                "Break",
                "WalkIn",
                "0771234567",
                "Break period review"
        );

        assertThatThrownBy(() ->
                appointmentService.book(patientUser.getId(), patientRequest)
        ).isInstanceOf(ConflictException.class)
                .hasMessageContaining("11:00–13:00 break");

        assertThatThrownBy(() ->
                appointmentService.bookWalkIn(manager.getId(), walkInRequest)
        ).isInstanceOf(ConflictException.class)
                .hasMessageContaining("11:00–13:00 break");

        Long appointmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE session_id = ?",
                Long.class,
                morning.getId()
        );
        Long allocatedNumbers = jdbcTemplate.queryForObject(
                "SELECT last_appointment_number FROM doctor_sessions WHERE id = ?",
                Long.class,
                morning.getId()
        );
        assertThat(appointmentCount).isZero();
        assertThat(allocatedNumbers).isZero();
    }

    private Doctor createDoctor(
            Role role,
            Specialisation specialisation,
            String unique
    ) {
        User user = userRepository.saveAndFlush(new User(
                role,
                "break.doctor." + unique + "@example.test",
                "test-password-hash"
        ));
        user.activateAfterVerification();
        userRepository.saveAndFlush(user);

        return doctorRepository.saveAndFlush(new Doctor(
                user,
                specialisation,
                "Break",
                "Doctor",
                "BREAK-" + unique,
                "Test qualification",
                "Temporary break test",
                new BigDecimal("1500.00")
        ));
    }

    private DoctorSession openSession(Doctor doctor, String unique) {
        DoctorSession session = new DoctorSession(
                doctor,
                null,
                LocalDate.of(2026, 11, 25),
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ConsultationType.ONLINE,
                ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION
        );
        session.updateSchedulingDetails(
                ClinicSchedule.MINUTES_PER_PATIENT,
                new BigDecimal("1500.00")
        );
        session.open();
        return doctorSessionRepository.saveAndFlush(session);
    }
}
