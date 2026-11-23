package com.medichannel.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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

import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
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
 * guest dashboard: 07:00-16:00, 10-minute patient slots, available doctors
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
                LocalTime.of(7, 0),
                LocalTime.of(16, 0),
                10
        );
        DoctorSession outsideHours = createSession(
                availableDoctor,
                date.plusDays(1),
                LocalTime.of(18, 0),
                LocalTime.of(21, 0),
                10
        );
        DoctorSession wrongSlot = createSession(
                availableDoctor,
                date.plusDays(2),
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                15
        );
        DoctorSession unavailableDoctorSession = createSession(
                unavailableDoctor,
                date.plusDays(3),
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                10
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
        assertThat(eligibleEntry.get("minutesPerPatient").asInt()).isEqualTo(10);
        assertThat(sessionIds(sessions)).doesNotContain(
                outsideHours.getId().toString(),
                wrongSlot.getId().toString(),
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
                                {"sessionId":"%s","reason":"Follow-up review"}
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

        // A direct booking attempt cannot bypass the published schedule or
        // book a doctor the guest dashboard marks unavailable.
        mockMvc.perform(
                post("/api/v1/appointments")
                        .with(customerJwt(patientUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"%s","reason":"Evening review"}
                                """.formatted(outsideHours.getId()))
        ).andExpect(status().isConflict());

        mockMvc.perform(
                post("/api/v1/appointments")
                        .with(customerJwt(patientUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"%s","reason":"Offline doctor"}
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
                LocalTime.of(7, 0),
                LocalTime.of(10, 0),
                10
        );
        DoctorSession afternoon = createSession(
                doctor,
                date,
                LocalTime.of(13, 0),
                LocalTime.of(16, 0),
                10
        );

        JsonNode morningResults = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/sessions").param("period", "08:00"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        assertThat(sessionIds(morningResults))
                .contains(morning.getId().toString())
                .doesNotContain(afternoon.getId().toString());

        JsonNode afternoonResults = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/sessions").param("period", "14:00"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        assertThat(sessionIds(afternoonResults))
                .contains(afternoon.getId().toString())
                .doesNotContain(morning.getId().toString());

        JsonNode boundaryResults = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/sessions").param("period", "10:00"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
        );
        assertThat(sessionIds(boundaryResults))
                .doesNotContain(
                        morning.getId().toString(),
                        afternoon.getId().toString()
                );
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
        DoctorSession session = new DoctorSession(
                doctor,
                null,
                date,
                start,
                end,
                ConsultationType.ONLINE,
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
