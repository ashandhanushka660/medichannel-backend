package com.medichannel.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.dto.session.DoctorSessionRequest;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.SessionStatus;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;
import com.medichannel.service.AppointmentService;
import com.medichannel.service.DoctorSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Practical queue behaviour: a delay affects the delayed booking and everyone
 * behind it, a cancellation hands its place to the next patient, and no role
 * can cancel without recording a reason. Sessions always use the published
 * 07:00-16:00 clinic day with 5-minute default slots.
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
@Transactional
class QueueRulesIntegrationTests {

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
    private AppointmentRepository appointmentRepository;

    @Autowired
    private PatientDataProtectionService dataProtectionService;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private DoctorSessionService doctorSessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void delayAffectsOnlyTheDelayedBookingAndEveryoneBehindIt() {
        Fixture fixture = new Fixture("delay");
        Appointment earlier = fixture.book(fixture.patient(1));
        Appointment target = fixture.book(fixture.patient(2));
        Appointment later = fixture.book(fixture.patient(3));

        appointmentService.delayAppointmentQueue(
                fixture.managerId,
                "MANAGER",
                target.getId(),
                10
        );

        assertThat(reload(earlier).getDelayMinutes()).isZero();
        assertThat(reload(target).getDelayMinutes()).isEqualTo(10);
        assertThat(reload(later).getDelayMinutes()).isEqualTo(10);
    }

    @Test
    void bookingResponseExposesQueuePlaceSlotAndFinishTime() {
        Fixture fixture = new Fixture("slot");
        Appointment booked = fixture.book(fixture.patient(1));

        var response = appointmentService
                .getMyAppointments(fixture.patient(1).getUser().getId())
                .stream()
                .filter(item -> item.appointmentId().equals(booked.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(response.appointmentNumber()).isNotNull();
        assertThat(response.bookedAt()).isNotNull();
        assertThat(response.estimatedConsultationTime()).isNotNull();
        assertThat(response.minutesPerPatient())
                .isEqualTo(ClinicSchedule.MINUTES_PER_PATIENT);
        assertThat(response.estimatedConsultationEndTime())
                .isEqualTo(response.estimatedConsultationTime()
                        .plusMinutes(ClinicSchedule.MINUTES_PER_PATIENT));
    }

    @Test
    void cancellationHandsTheQueuePlaceToTheNextPatient() {
        Fixture fixture = new Fixture("cancel");
        fixture.book(fixture.patient(1));
        Appointment cancelled = fixture.book(fixture.patient(2));
        Appointment next = fixture.book(fixture.patient(3));

        LocalDateTime before = appointmentService
                .getMyAppointments(fixture.patient(3).getUser().getId())
                .stream()
                .filter(item -> item.appointmentId().equals(next.getId()))
                .findFirst()
                .orElseThrow()
                .estimatedConsultationTime();

        appointmentService.cancelAppointment(
                fixture.managerId,
                "MANAGER",
                cancelled.getId(),
                "Patient asked to cancel"
        );

        LocalDateTime after = appointmentService
                .getMyAppointments(fixture.patient(3).getUser().getId())
                .stream()
                .filter(item -> item.appointmentId().equals(next.getId()))
                .findFirst()
                .orElseThrow()
                .estimatedConsultationTime();

        assertThat(after).isEqualTo(before.minusMinutes(
                ClinicSchedule.MINUTES_PER_PATIENT
        ));
    }

    @Test
    void everyCancellationRoleRequiresAReason() {
        Fixture fixture = new Fixture("reason");
        Appointment managerTarget = fixture.book(fixture.patient(1));
        Appointment doctorTarget = fixture.book(fixture.patient(2));

        assertThatThrownBy(() -> appointmentService.cancelAppointment(
                fixture.managerId,
                "MANAGER",
                managerTarget.getId(),
                "   "
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");

        assertThatThrownBy(() -> appointmentService.declineAppointmentByDoctor(
                fixture.doctor.getUser().getId(),
                doctorTarget.getId(),
                ""
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");
    }

    @Test
    void sessionsMustCoverThePublishedClinicDayWithConfigurableSlots() {
        Fixture fixture = new Fixture("schedule");

        assertThatThrownBy(() -> doctorSessionService.createSession(
                new DoctorSessionRequest(
                        fixture.doctor.getId(),
                        null,
                        LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(40),
                        LocalTime.of(9, 0),
                        LocalTime.of(12, 0),
                        ConsultationType.ONLINE,
                        18,
                        10,
                        new BigDecimal("1500.00")
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("07:00–16:00");

        var created = doctorSessionService.createSession(
                new DoctorSessionRequest(
                        fixture.doctor.getId(),
                        null,
                        LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(41),
                        ClinicSchedule.START_TIME,
                        ClinicSchedule.END_TIME,
                        ConsultationType.ONLINE,
                        ClinicSchedule.MAX_APPOINTMENTS_PER_DAY,
                        ClinicSchedule.MINUTES_PER_PATIENT,
                        new BigDecimal("1500.00")
                )
        );

        assertThat(created.startTime()).isEqualTo(ClinicSchedule.START_TIME);
        assertThat(created.endTime()).isEqualTo(ClinicSchedule.END_TIME);
    }

    @Test
    void capacityAndSlotRulesAcceptThePublishedRanges() {
        Fixture fixture = new Fixture("limits");
        LocalDate base = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(50);

        var smallest = doctorSessionService.createSession(
                sessionRequest(fixture, base, 1, 5)
        );
        var largest = doctorSessionService.createSession(
                sessionRequest(fixture, base.plusDays(1), 60, 9)
        );
        var longestSlot = doctorSessionService.createSession(
                sessionRequest(fixture, base.plusDays(2), 12, 30)
        );

        assertThat(smallest.maxAppointments()).isEqualTo(1);
        assertThat(largest.maxAppointments()).isEqualTo(60);
        assertThat(longestSlot.minutesPerPatient()).isEqualTo(30);
    }

    @Test
    void capacityAndSlotRulesRejectValuesOutsideThePublishedRanges() {
        Fixture fixture = new Fixture("limits-bad");
        LocalDate base = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(60);

        assertThatThrownBy(() -> doctorSessionService.createSession(
                sessionRequest(fixture, base, 0, 5)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 60");

        assertThatThrownBy(() -> doctorSessionService.createSession(
                sessionRequest(fixture, base.plusDays(1), 61, 5)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 60");

        assertThatThrownBy(() -> doctorSessionService.createSession(
                sessionRequest(fixture, base.plusDays(2), 1, 4)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 5 and 30");

        assertThatThrownBy(() -> doctorSessionService.createSession(
                sessionRequest(fixture, base.plusDays(3), 1, 31)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 5 and 30");
    }

    @Test
    void sessionsAreNeverDeletedOnlyStatusChanged() {
        Fixture fixture = new Fixture("retained");
        UUID sessionId = fixture.session.getId();

        Integer triggers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger "
                        + "WHERE tgname = 'trg_protect_doctor_session_history' "
                        + "AND NOT tgisinternal",
                Integer.class
        );
        assertThat(triggers).isEqualTo(1);

        doctorSessionService.cancelSession(
                sessionId,
                fixture.managerId,
                "MANAGER",
                "Clinic closed"
        );

        assertThat(doctorSessionRepository.findById(sessionId))
                .isPresent()
                .get()
                .extracting(DoctorSession::getStatus)
                .isEqualTo(SessionStatus.CANCELLED);
    }

    private DoctorSessionRequest sessionRequest(
            Fixture fixture,
            LocalDate date,
            int maxAppointments,
            int minutesPerPatient
    ) {
        return new DoctorSessionRequest(
                fixture.doctor.getId(),
                null,
                date,
                ClinicSchedule.START_TIME,
                ClinicSchedule.END_TIME,
                ConsultationType.ONLINE,
                maxAppointments,
                minutesPerPatient,
                new BigDecimal("1500.00")
        );
    }

    private Appointment reload(Appointment appointment) {
        return appointmentRepository.findById(appointment.getId()).orElseThrow();
    }

    private final class Fixture {
        private final String unique = UUID.randomUUID().toString().replace("-", "");
        private final Role managerRole;
        private final Doctor doctor;
        private final DoctorSession session;
        private final UUID managerId;
        private final Map<Integer, Patient> patients = new HashMap<>();

        private Fixture(String label) {
            Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
            managerRole = roleRepository.findByName("MANAGER").orElseThrow();
            Specialisation specialisation = specialisationRepository.saveAndFlush(
                    new Specialisation(
                            "Queue " + label + " " + unique,
                            "Temporary queue rule test"
                    )
            );
            doctor = createDoctor(doctorRole, specialisation, label);
            session = new DoctorSession(
                    doctor,
                    null,
                    LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(35),
                    ClinicSchedule.START_TIME,
                    ClinicSchedule.END_TIME,
                    ConsultationType.ONLINE,
                    ClinicSchedule.MAX_APPOINTMENTS_PER_DAY
            );
            session.updateSchedulingDetails(
                    ClinicSchedule.MINUTES_PER_PATIENT,
                    new BigDecimal("1500.00")
            );
            session.open();
            doctorSessionRepository.saveAndFlush(session);
            managerId = createManager().getId();
        }

        private Doctor createDoctor(Role role, Specialisation specialisation, String label) {
            User user = userRepository.saveAndFlush(new User(
                    role,
                    "queue." + label + "." + unique + "@example.test",
                    "test-password-hash"
            ));
            user.activateAfterVerification();
            userRepository.saveAndFlush(user);
            return doctorRepository.saveAndFlush(new Doctor(
                    user,
                    specialisation,
                    label,
                    "Doctor",
                    "Q-" + label.toUpperCase() + "-" + unique,
                    "Test qualification",
                    "Temporary queue rule test",
                    new BigDecimal("1500.00")
            ));
        }

        private User createManager() {
            User user = userRepository.saveAndFlush(new User(
                    managerRole,
                    "queue.manager." + unique + "@example.test",
                    "test-password-hash"
            ));
            user.activateAfterVerification();
            return userRepository.saveAndFlush(user);
        }

        private Patient patient(int number) {
            if (patients.containsKey(number)) {
                return patients.get(number);
            }
            User user = userRepository.saveAndFlush(new User(
                    roleRepository.findByName("CUSTOMER").orElseThrow(),
                    "queue.patient" + number + "." + unique + "@example.test",
                    "test-password-hash"
            ));
            user.activateAfterVerification();
            userRepository.saveAndFlush(user);
            Patient patient = patientRepository.saveAndFlush(new Patient(
                    user,
                    dataProtectionService.encrypt("Queue"),
                    dataProtectionService.encrypt("Patient " + number),
                    dataProtectionService.encrypt("077123456" + number),
                    null,
                    dataProtectionService.encrypt("2000-01-01"),
                    dataProtectionService.encrypt("OTHER")
            ));
            patients.put(number, patient);
            return patient;
        }

        private Appointment book(Patient patient) {
            return appointmentRepository.saveAndFlush(new Appointment(
                    session,
                    patient,
                    dataProtectionService.encrypt("Queue rule test")
            ));
        }
    }
}
