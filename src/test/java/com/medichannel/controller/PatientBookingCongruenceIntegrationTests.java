package com.medichannel.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Import;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.config.WorkHoursClockTestConfiguration;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Room;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.RoomType;
import com.medichannel.enums.SessionStatus;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The patient booking feed must match the availability advertised on the
 * guest dashboard: the two published windows, five-minute slots, available doctors
 * only. Booking then gives the patient the next number in that session's
 * queue and appears in "My appointments" as before.
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
@Import(WorkHoursClockTestConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class PatientBookingCongruenceIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private SpecialisationRepository specialisationRepository;

    @Autowired
    private DoctorSessionRepository doctorSessionRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private PatientDataProtectionService dataProtectionService;

    @Test
    void congruentAvailableSessionsOnlyAndBookingTakesTheNextQueueNumber()
            throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Role customerRole = roleRepository.findByName("CUSTOMER").orElseThrow();
        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Congruence Test " + unique,
                        "Temporary patient booking congruence test"
                )
        );

        Doctor availableDoctor = createDoctor(
                doctorRole,
                specialisation,
                "Available",
                "Doctor",
                "CONG-A-" + unique,
                unique
        );
        Doctor unavailableDoctor = createDoctor(
                doctorRole,
                specialisation,
                "Unavailable",
                "Doctor",
                "CONG-U-" + unique,
                unique
        );
        unavailableDoctor.updateAvailability(false);
        doctorRepository.saveAndFlush(unavailableDoctor);

        LocalDate date = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(30);

        DoctorSession eligible = createSession(
                availableDoctor,
                date,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                5
        );
        DoctorSession outsideHours = createSession(
                availableDoctor,
                date.plusDays(1),
                LocalTime.of(18, 0),
                LocalTime.of(21, 0),
                5
        );
        DoctorSession outsideHoursAgain = createSession(
                availableDoctor,
                date.plusDays(2),
                LocalTime.of(17, 0),
                LocalTime.of(20, 0),
                5
        );
        DoctorSession unavailableDoctorSession = createSession(
                unavailableDoctor,
                date.plusDays(3),
                ClinicSchedule.EVENING_START,
                ClinicSchedule.EVENING_END,
                5
        );

        User patientUser = new User(
                customerRole,
                "congruence.patient." + unique + "@example.test",
                "test-password-hash"
        );
        patientUser.activateAfterVerification();
        patientUser = userRepository.saveAndFlush(patientUser);

        Patient patient = patientRepository.saveAndFlush(
                new Patient(
                        patientUser,
                        dataProtectionService.encrypt("Congruence"),
                        dataProtectionService.encrypt("Patient"),
                        dataProtectionService.encrypt("0771234567"),
                        null,
                        dataProtectionService.encrypt("2000-01-01"),
                        dataProtectionService.encrypt("OTHER")
                )
        );

        String sessionsBody = mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sessions = objectMapper.readTree(sessionsBody);
        assertThat(sessionIds(sessions)).contains(eligible.getId().toString());
        JsonNode eligibleEntry = sessions.get(sessionIds(sessions).indexOf(
                eligible.getId().toString()
        ));
        assertThat(eligibleEntry.get("doctorAvailable").asBoolean()).isTrue();
        assertThat(eligibleEntry.get("minutesPerPatient").asInt()).isEqualTo(5);
        assertThat(sessionIds(sessions)).doesNotContain(
                outsideHours.getId().toString(),
                outsideHoursAgain.getId().toString(),
                unavailableDoctorSession.getId().toString()
        );

        JsonNode before = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/sessions/" + eligible.getId() + "/queue"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        assertThat(before.get("nextNumber").asInt()).isEqualTo(1);

        String bookingBody = mockMvc.perform(
                post("/api/v1/appointments")
                        .with(customerJwt(patientUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"%s","reason":"Follow-up review","period":"MORNING"}
                                """.formatted(eligible.getId()))
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode booking = objectMapper.readTree(bookingBody);
        assertThat(booking.get("appointmentNumber").asInt()).isEqualTo(1);

        JsonNode after = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/sessions/" + eligible.getId() + "/queue"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        assertThat(after.get("nextNumber").asInt()).isEqualTo(2);

        String mineBody = mockMvc.perform(
                get("/api/v1/appointments/me")
                        .with(customerJwt(patientUser.getId()))
        )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode mine = objectMapper.readTree(mineBody);
        assertThat(mine).anySatisfy((item) -> {
            assertThat(item.get("sessionId").asText())
                    .isEqualTo(eligible.getId().toString());
            assertThat(item.get("appointmentNumber").asInt()).isEqualTo(1);
        });

        // A period choice must match the selected session's published window.
        mockMvc.perform(
                post("/api/v1/appointments")
                        .with(customerJwt(patientUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"%s","reason":"Wrong period","period":"EVENING"}
                                """.formatted(eligible.getId()))
        ).andExpect(status().isBadRequest());

        // A direct booking attempt cannot bypass the published schedule or
        // book a doctor the guest dashboard marks unavailable.
        mockMvc.perform(
                post("/api/v1/appointments")
                        .with(customerJwt(patientUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"%s","reason":"Evening review","period":"MORNING"}
                                """.formatted(outsideHours.getId()))
        ).andExpect(status().isConflict());

        mockMvc.perform(
                post("/api/v1/appointments")
                        .with(customerJwt(patientUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"%s","reason":"Offline doctor","period":"EVENING"}
                                """.formatted(unavailableDoctorSession.getId()))
        ).andExpect(status().isConflict());

        long activeForEligible = appointmentRepository
                .countBySession_IdAndStatusIn(
                        eligible.getId(),
                        java.util.List.of(
                                com.medichannel.enums.AppointmentStatus.BOOKED,
                                com.medichannel.enums.AppointmentStatus.ARRIVED,
                                com.medichannel.enums.AppointmentStatus.IN_CONSULTATION
                        )
                );
        assertThat(activeForEligible).isEqualTo(1);
        assertThat(patient.getId()).isNotNull();
    }

    @Test
    void selectedHourFiltersSessionsToTheMatchingClinicPeriod()
            throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Period Test " + unique,
                        "Temporary clinic period test"
                )
        );
        Doctor doctor = createDoctor(
                doctorRole,
                specialisation,
                "Period",
                "Doctor",
                "CONG-P-" + unique,
                unique
        );

        LocalDate date = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(31);
        DoctorSession morning = createSession(
                doctor,
                date,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                5
        );
        DoctorSession afternoon = createSession(
                doctor,
                date,
                ClinicSchedule.EVENING_START,
                ClinicSchedule.EVENING_END,
                5
        );

        JsonNode morningResults = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/sessions").param("period", "07:00"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        assertThat(sessionIds(morningResults))
                .contains(morning.getId().toString())
                .doesNotContain(afternoon.getId().toString());

        JsonNode afternoonResults = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/sessions").param("period", "13:00"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        assertThat(sessionIds(afternoonResults))
                .contains(afternoon.getId().toString())
                .doesNotContain(morning.getId().toString());

        JsonNode lunchGapResults = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/sessions").param("period", "11:00"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        assertThat(sessionIds(lunchGapResults))
                .doesNotContain(
                        morning.getId().toString(),
                        afternoon.getId().toString()
                );
    }

    @Test
    void publicFeedAppliesThePhysicalAndOnlineBookingDeadlines() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Deadline Test " + unique,
                        "Temporary booking deadline test"
                )
        );
        Doctor physicalSoonDoctor = createDoctor(
                doctorRole, specialisation, "Physical Soon", "Doctor",
                "DL-PS-" + unique, unique
        );
        Doctor physicalBookableDoctor = createDoctor(
                doctorRole, specialisation, "Physical Open", "Doctor",
                "DL-PO-" + unique, unique
        );
        Doctor onlineSoonDoctor = createDoctor(
                doctorRole, specialisation, "Online Soon", "Doctor",
                "DL-OS-" + unique, unique
        );
        Doctor onlineBookableDoctor = createDoctor(
                doctorRole, specialisation, "Online Open", "Doctor",
                "DL-OO-" + unique, unique
        );

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        DoctorSession physicalTooSoon = createSession(
                physicalSoonDoctor,
                today,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                5,
                ConsultationType.PHYSICAL
        );
        DoctorSession physicalBookable = createSession(
                physicalBookableDoctor,
                today,
                ClinicSchedule.EVENING_START,
                ClinicSchedule.EVENING_END,
                5,
                ConsultationType.PHYSICAL
        );
        DoctorSession onlineTooSoon = createSession(
                onlineSoonDoctor,
                today,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                5,
                ConsultationType.ONLINE
        );
        DoctorSession onlineBookable = createSession(
                onlineBookableDoctor,
                today,
                ClinicSchedule.EVENING_START,
                ClinicSchedule.EVENING_END,
                5,
                ConsultationType.ONLINE
        );
        DoctorSession tomorrow = createSession(
                physicalSoonDoctor,
                today.plusDays(1),
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                5,
                ConsultationType.ONLINE
        );

        List<UUID> ids = doctorSessionRepository.findPublicOpenSessions(
                SessionStatus.OPEN,
                today,
                ConsultationType.PHYSICAL,
                LocalTime.of(9, 30),
                LocalTime.of(11, 0),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ClinicSchedule.EVENING_START,
                ClinicSchedule.EVENING_END,
                ClinicSchedule.MINUTES_PER_PATIENT,
                ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION,
                List.of(
                        AppointmentStatus.BOOKED,
                        AppointmentStatus.ARRIVED,
                        AppointmentStatus.IN_CONSULTATION
                )
        ).stream().map(DoctorSession::getId).toList();

        assertThat(ids).contains(
                physicalBookable.getId(),
                onlineBookable.getId(),
                tomorrow.getId()
        ).doesNotContain(physicalTooSoon.getId(), onlineTooSoon.getId());
    }

    private java.util.List<String> sessionIds(JsonNode sessions) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        sessions.forEach((session) -> ids.add(session.get("sessionId").asText()));
        return ids;
    }

    private Doctor createDoctor(
            Role role,
            Specialisation specialisation,
            String firstName,
            String lastName,
            String registrationNumber,
            String unique
    ) {
        User user = userRepository.saveAndFlush(
                new User(
                        role,
                        "congruence." + registrationNumber.toLowerCase()
                                + "@example.test",
                        "test-password-hash"
                )
        );
        user.activateAfterVerification();
        user = userRepository.saveAndFlush(user);

        return doctorRepository.saveAndFlush(
                new Doctor(
                        user,
                        specialisation,
                        firstName,
                        lastName,
                        registrationNumber,
                        "Test qualification",
                        "Temporary congruence test",
                        new BigDecimal("1500.00")
                )
        );
    }

    private DoctorSession createSession(
            Doctor doctor,
            LocalDate date,
            LocalTime start,
            LocalTime end,
            int minutesPerPatient
    ) {
        return createSession(
                doctor,
                date,
                start,
                end,
                minutesPerPatient,
                ConsultationType.ONLINE
        );
    }

    private DoctorSession createSession(
            Doctor doctor,
            LocalDate date,
            LocalTime start,
            LocalTime end,
            int minutesPerPatient,
            ConsultationType consultationType
    ) {
        Room room = null;
        if (consultationType == ConsultationType.PHYSICAL) {
            String suffix = UUID.randomUUID()
                    .toString().replace("-", "").substring(0, 12);
            room = roomRepository.saveAndFlush(new Room(
                    "DL-" + suffix,
                    "Deadline Test Room",
                    "Local Test Hospital",
                    RoomType.CONSULTATION,
                    4
            ));
        }

        DoctorSession session = new DoctorSession(
                doctor,
                room,
                date,
                start,
                end,
                consultationType,
                4
        );
        session.updateSchedulingDetails(
                minutesPerPatient,
                new BigDecimal("1500.00")
        );
        session.open();
        return doctorSessionRepository.saveAndFlush(session);
    }

    private org.springframework.test.web.servlet.request
            .RequestPostProcessor customerJwt(UUID userId) {
        return jwt()
                .jwt(token -> token
                        .subject(userId.toString())
                        .claim("role", "CUSTOMER")
                        .claim("email", "congruence-test@example.test")
                )
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
