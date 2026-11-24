package com.medichannel.controller;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
import com.medichannel.dto.appointment.AppointmentBookingRequest;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Room;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.RoomType;
import com.medichannel.enums.SessionStatus;
import com.medichannel.exception.ConflictException;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;
import com.medichannel.service.AppointmentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cutoffs come from each session's own start time, not from a doctor-wide
 * arrival/departure window. Fixed at 11:15 Asia/Colombo: the morning session
 * is closed for both modes, the afternoon physical session is closed, and the
 * afternoon online session is still bookable.
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
@Import(BookingCutoffIntegrationTests.FixedClockConfig.class)
@Transactional
class BookingCutoffIntegrationTests {

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
    private PatientRepository patientRepository;

    @Autowired
    private DoctorSessionRepository doctorSessionRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private PatientDataProtectionService dataProtectionService;

    @Autowired
    private AppointmentService appointmentService;

    @Test
    void cutoffsAreCalculatedFromEachSessionsOwnStart() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Role customerRole = roleRepository.findByName("CUSTOMER").orElseThrow();

        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Cutoff Test " + unique,
                        "Temporary booking cutoff test"
                )
        );

        LocalDate today = LocalDate.of(2026, 11, 25);
        Doctor morningDoctor = createDoctor(
                doctorRole, specialisation, "Morning", unique + "-M"
        );
        Doctor afternoonPhysicalDoctor = createDoctor(
                doctorRole, specialisation, "Physical", unique + "-P"
        );
        Doctor afternoonOnlineDoctor = createDoctor(
                doctorRole, specialisation, "Online", unique + "-O"
        );

        DoctorSession morning = openSession(
                morningDoctor,
                today,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ConsultationType.PHYSICAL
        );
        DoctorSession afternoonPhysical = openSession(
                afternoonPhysicalDoctor,
                today,
                ClinicSchedule.AFTERNOON_START,
                ClinicSchedule.AFTERNOON_END,
                ConsultationType.PHYSICAL
        );
        DoctorSession afternoonOnline = openSession(
                afternoonOnlineDoctor,
                today,
                ClinicSchedule.AFTERNOON_START,
                ClinicSchedule.AFTERNOON_END,
                ConsultationType.ONLINE
        );

        User patientUser = userRepository.saveAndFlush(new User(
                customerRole,
                "cutoff.patient." + unique + "@example.test",
                "test-password-hash"
        ));
        patientUser.activateAfterVerification();
        userRepository.saveAndFlush(patientUser);
        patientRepository.saveAndFlush(new Patient(
                patientUser,
                dataProtectionService.encrypt("Cutoff"),
                dataProtectionService.encrypt("Patient"),
                dataProtectionService.encrypt("0771234567"),
                null,
                dataProtectionService.encrypt("2000-01-01"),
                dataProtectionService.encrypt("OTHER")
        ));

        AppointmentBookingRequest morningRequest =
                new AppointmentBookingRequest(morning.getId(), "Morning review");
        AppointmentBookingRequest physicalRequest = new AppointmentBookingRequest(
                afternoonPhysical.getId(),
                "Afternoon physical review"
        );
        AppointmentBookingRequest onlineRequest = new AppointmentBookingRequest(
                afternoonOnline.getId(),
                "Afternoon online review"
        );

        assertThatThrownBy(() ->
                appointmentService.book(patientUser.getId(), morningRequest)
        ).isInstanceOf(ConflictException.class)
                .hasMessageContaining("booking deadline");

        assertThatThrownBy(() ->
                appointmentService.book(patientUser.getId(), physicalRequest)
        ).isInstanceOf(ConflictException.class)
                .hasMessageContaining("booking deadline");

        var booked = appointmentService.book(
                patientUser.getId(),
                onlineRequest
        );
        assertThat(booked.sessionId()).isEqualTo(afternoonOnline.getId());
    }

    private Doctor createDoctor(
            Role role,
            Specialisation specialisation,
            String name,
            String registrationSuffix
    ) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        User user = userRepository.saveAndFlush(new User(
                role,
                "cutoff." + registrationSuffix.toLowerCase()
                        + "." + unique + "@example.test",
                "test-password-hash"
        ));
        user.activateAfterVerification();
        userRepository.saveAndFlush(user);

        return doctorRepository.saveAndFlush(new Doctor(
                user,
                specialisation,
                name,
                "Doctor",
                "CUTOFF-" + registrationSuffix + "-" + unique,
                "Test qualification",
                "Temporary booking cutoff test",
                new BigDecimal("1500.00")
        ));
    }

    private DoctorSession openSession(
            Doctor doctor,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            ConsultationType type
    ) {
        Room room = null;
        if (type == ConsultationType.PHYSICAL) {
            String suffix = UUID.randomUUID()
                    .toString().replace("-", "").substring(0, 12);
            room = roomRepository.saveAndFlush(new Room(
                    "CUT-" + suffix,
                    "Cutoff Test Room",
                    "Local Test Hospital",
                    RoomType.CONSULTATION,
                    4
            ));
        }

        DoctorSession session = new DoctorSession(
                doctor,
                room,
                date,
                startTime,
                endTime,
                type,
                ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION
        );
        session.updateSchedulingDetails(
                ClinicSchedule.MINUTES_PER_PATIENT,
                new BigDecimal("1500.00")
        );
        session.open();
        DoctorSession saved = doctorSessionRepository.saveAndFlush(session);
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.OPEN);
        return saved;
    }
}
