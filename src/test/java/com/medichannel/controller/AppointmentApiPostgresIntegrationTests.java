package com.medichannel.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Import;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.config.WorkHoursClockTestConfiguration;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Room;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.RoomType;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class AppointmentApiPostgresIntegrationTests {

    private static final String APPOINTMENTS_URL =
            "/api/v1/appointments";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    private RoomRepository roomRepository;

    @Autowired
    private DoctorSessionRepository doctorSessionRepository;

    @Autowired
    private PatientDataProtectionService dataProtectionService;

    @Autowired
    private EntityManager entityManager;

    /**
     * The booking rate limiter counts in a committed, shared database row.
     * Clear it in an independent transaction so this class never inherits
     * bookings made by earlier test classes; the booking assertions themselves
     * are unchanged.
     */
    @BeforeEach
    void resetBookingRateLimits() {
        TransactionTemplate template =
                new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        template.executeWithoutResult(status ->
                jdbcTemplate.update(
                        "DELETE FROM api_rate_limits "
                                + "WHERE rate_key LIKE 'booking:%'"
                )
        );
    }

    @Test
    void customerCanBookViewAndCancelOwnAppointment() throws Exception {

        TestFixture fixture = createFixture();

        String bookingRequest = bookingJson(
                fixture.sessionId(),
                "Persistent headache"
        );

        // 1. Book an appointment.
        String responseBody = mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequest)
        )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentNumber").value(1))
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode bookingResponse = objectMapper.readTree(responseBody);

        String appointmentId = bookingResponse
                .get("appointmentId")
                .asText();

        assertThat(appointmentId).isNotBlank();

        // 2. Verify the customer's appointment history.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].appointmentId")
                        .value(appointmentId));

        // 3. Retrieve the individual appointment.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId)
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId")
                        .value(appointmentId))
                .andExpect(jsonPath("$.appointmentNumber").value(1));

        // 4. Cancel the unpaid appointment with a mandatory reason.
        mockMvc.perform(
                patch(APPOINTMENTS_URL + "/" + appointmentId + "/cancel")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "Patient request"}
                                """)
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason")
                        .value("Patient request"));

        // 5. Confirm cancellation remains in appointment history.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));

        // 6. Confirm the database retained the cancelled record.
        Integer appointmentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM appointments
                WHERE id = ?
                  AND status = 'CANCELLED'
                  AND cancelled_at IS NOT NULL
                """,
                Integer.class,
                UUID.fromString(appointmentId)
        );

        assertThat(appointmentCount).isEqualTo(1);
    }

    @Test
    void patientCanClearOwnClosedAppointmentWithoutDeletingTheRecord()
            throws Exception {

        TestFixture fixture = createFixture();

        String appointmentId = bookAndCancel(fixture, "History clear test");

        mockMvc.perform(
                get(APPOINTMENTS_URL + "/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(
                patch(APPOINTMENTS_URL + "/" + appointmentId + "/hide")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
        )
                .andExpect(status().isNoContent());

        // Only the patient's list view changes.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Direct access and the underlying appointment still exist.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId)
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Integer retained = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM appointments
                WHERE id = ?
                  AND status = 'CANCELLED'
                  AND patient_hidden_at IS NOT NULL
                """,
                Integer.class,
                UUID.fromString(appointmentId)
        );
        assertThat(retained).isEqualTo(1);

        entityManager.flush();
        Integer audits = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_logs
                WHERE action = 'APPOINTMENT_HISTORY_HIDDEN'
                  AND appointment_id = ?
                  AND user_id = ?
                """,
                Integer.class,
                UUID.fromString(appointmentId),
                fixture.patientUserId()
        );
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void activeAppointmentCannotBeClearedFromHistory() throws Exception {

        TestFixture fixture = createFixture();

        String responseBody = mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(
                                fixture.sessionId(),
                                "Active history clear test"
                        ))
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String appointmentId = objectMapper.readTree(responseBody)
                .get("appointmentId")
                .asText();

        mockMvc.perform(
                patch(APPOINTMENTS_URL + "/" + appointmentId + "/hide")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
        )
                .andExpect(status().isConflict());

        mockMvc.perform(
                get(APPOINTMENTS_URL + "/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        Integer hidden = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM appointments
                WHERE id = ? AND patient_hidden_at IS NOT NULL
                """,
                Integer.class,
                UUID.fromString(appointmentId)
        );
        assertThat(hidden).isZero();
    }

    @Test
    void doctorCanClearOwnClosedAppointmentWithoutAffectingPatientHistory()
            throws Exception {

        TestFixture fixture = createFixture();
        UUID doctorUserId = doctorUserId(fixture.sessionId());
        String appointmentId = bookAndCancel(fixture, "Doctor clear test");

        mockMvc.perform(
                get("/api/v1/doctor/sessions/"
                        + fixture.sessionId() + "/queue")
                        .with(doctorJwt(doctorUserId))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointments.length()").value(1))
                .andExpect(jsonPath("$.appointments[0].appointmentId")
                        .value(appointmentId));

        mockMvc.perform(
                patch("/api/v1/doctor/appointments/"
                        + appointmentId + "/hide")
                        .with(doctorJwt(doctorUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
        )
                .andExpect(status().isNoContent());

        mockMvc.perform(
                get("/api/v1/doctor/sessions/"
                        + fixture.sessionId() + "/queue")
                        .with(doctorJwt(doctorUserId))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointments.length()").value(0));

        // The patient did not clear this row, so it remains in their history.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/me")
                        .with(customerJwt(fixture.patientUserId()))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        Integer visibility = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM appointments
                WHERE id = ?
                  AND doctor_hidden_at IS NOT NULL
                  AND patient_hidden_at IS NULL
                """,
                Integer.class,
                UUID.fromString(appointmentId)
        );
        assertThat(visibility).isEqualTo(1);
    }

    @Test
    void patientCancellationRequiresAReasonAndAnUnpaidBooking()
            throws Exception {

        TestFixture fixture = createFixture();

        String responseBody = mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(
                                fixture.sessionId(),
                                "Cancellation rule test"
                        ))
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String appointmentId = objectMapper.readTree(responseBody)
                .get("appointmentId")
                .asText();

        // A blank reason is rejected by request validation.
        mockMvc.perform(
                patch(APPOINTMENTS_URL + "/" + appointmentId + "/cancel")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "   "}
                                """)
        )
                .andExpect(status().isBadRequest());

        // Once paid, the patient can no longer cancel the appointment.
        mockMvc.perform(
                post(APPOINTMENTS_URL + "/" + appointmentId + "/payments")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method": "CARD"}
                                """)
        )
                .andExpect(status().isCreated());

        mockMvc.perform(
                patch(APPOINTMENTS_URL + "/" + appointmentId + "/cancel")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "Changed my mind"}
                                """)
        )
                .andExpect(status().isConflict());
    }

    @Test
    void customerCannotBookTwiceInTheSameSession() throws Exception {

        TestFixture fixture = createFixture();

        String request = bookingJson(
                fixture.sessionId(),
                "First booking"
        );

        mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        )
                .andExpect(status().isCreated());

        // The same patient attempts another active booking.
        mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(
                                fixture.sessionId(),
                                "Duplicate booking"
                        ))
        )
                .andExpect(status().isConflict());

        Integer appointmentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM appointments
                WHERE session_id = ?
                """,
                Integer.class,
                fixture.sessionId()
        );

        Integer appointmentCounter = jdbcTemplate.queryForObject(
                """
                SELECT last_appointment_number
                FROM doctor_sessions
                WHERE id = ?
                """,
                Integer.class,
                fixture.sessionId()
        );

        assertThat(appointmentCount).isEqualTo(1);
        assertThat(appointmentCounter).isEqualTo(1);
    }

    @Test
    void customerCannotRetrieveAnotherCustomersAppointment()
            throws Exception {

        TestFixture owner = createFixture();
        TestFixture anotherCustomer = createFixture();

        String responseBody = mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(owner.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(
                                owner.sessionId(),
                                "Private appointment"
                        ))
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String appointmentId = objectMapper
                .readTree(responseBody)
                .get("appointmentId")
                .asText();

        // A different authenticated customer must not access it.
        mockMvc.perform(
                get(APPOINTMENTS_URL + "/" + appointmentId)
                        .with(customerJwt(
                                anotherCustomer.patientUserId()
                        ))
        )
                .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedCustomerCannotBookAnAppointment()
            throws Exception {

        TestFixture fixture = createFixture();

        mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(
                                fixture.sessionId(),
                                "Unauthenticated booking"
                        ))
        )
                .andExpect(status().isUnauthorized());
    }

    private String bookAndCancel(TestFixture fixture, String reason)
            throws Exception {

        String responseBody = mockMvc.perform(
                post(APPOINTMENTS_URL)
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(fixture.sessionId(), reason))
        )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String appointmentId = objectMapper.readTree(responseBody)
                .get("appointmentId")
                .asText();

        mockMvc.perform(
                patch(APPOINTMENTS_URL + "/" + appointmentId + "/cancel")
                        .with(customerJwt(fixture.patientUserId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "History visibility test"}
                                """)
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        return appointmentId;
    }

    private UUID doctorUserId(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT d.user_id
                FROM doctor_sessions s
                JOIN doctors d ON d.id = s.doctor_id
                WHERE s.id = ?
                """,
                UUID.class,
                sessionId
        );
    }

    private TestFixture createFixture() {

        String unique = UUID.randomUUID()
                .toString()
                .replace("-", "");

        Role doctorRole = roleRepository.findByName("DOCTOR")
                .orElseThrow();

        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow();

        // Create a doctor account.
        User doctorUser = userRepository.saveAndFlush(
                new User(
                        doctorRole,
                        "api.doctor." + unique + "@example.test",
                        "test-password-hash"
                )
        );

        Specialisation specialisation =
                specialisationRepository.saveAndFlush(
                        new Specialisation(
                                "API Test " + unique,
                                "Temporary appointment API test"
                        )
                );

        Doctor doctor = doctorRepository.saveAndFlush(
                new Doctor(
                        doctorUser,
                        specialisation,
                        "API",
                        "TestDoctor",
                        "API-TEST-" + unique,
                        "Test qualification",
                        "Temporary API integration test",
                        new BigDecimal("1500.00")
                )
        );

        // Create an active, email-verified customer.
        User patientUser = new User(
                customerRole,
                "api.patient." + unique + "@example.test",
                "test-password-hash"
        );

        patientUser.activateAfterVerification();

        patientUser = userRepository.saveAndFlush(patientUser);

        Patient patient = patientRepository.saveAndFlush(
                new Patient(
                        patientUser,
                        dataProtectionService.encrypt("Test"),
                        dataProtectionService.encrypt("Patient"),
                        dataProtectionService.encrypt("0771234567"),
                        null,
                        dataProtectionService.encrypt("2000-01-01"),
                        dataProtectionService.encrypt("OTHER")
                )
        );

        // Create a second customer for ownership testing.
        User secondPatientUser = new User(
                customerRole,
                "api.patient.second." + unique + "@example.test",
                "test-password-hash"
        );

        secondPatientUser.activateAfterVerification();

        secondPatientUser = userRepository.saveAndFlush(
                secondPatientUser
        );

        patientRepository.saveAndFlush(
                new Patient(
                        secondPatientUser,
                        dataProtectionService.encrypt("Second"),
                        dataProtectionService.encrypt("Patient"),
                        dataProtectionService.encrypt("0771234568"),
                        null,
                        dataProtectionService.encrypt("2001-01-01"),
                        dataProtectionService.encrypt("OTHER")
                )
        );

        Room room = roomRepository.saveAndFlush(
                new Room(
                        "API-" + unique.substring(0, 12),
                        "API Test Room",
                        "Local Test Hospital",
                        RoomType.CONSULTATION,
                        4
                )
        );

        DoctorSession session = new DoctorSession(
                doctor,
                room,
                LocalDate.now(
                        ZoneId.of("Asia/Colombo")
                ).plusDays(30),
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ConsultationType.PHYSICAL,
                4
        );

        session.updateSchedulingDetails(
                ClinicSchedule.MINUTES_PER_PATIENT,
                new BigDecimal("1500.00")
        );

        session.open();

        session = doctorSessionRepository.saveAndFlush(session);

        return new TestFixture(
                patientUser.getId(),
                patient.getId(),
                secondPatientUser.getId(),
                session.getId()
        );
    }

    private String bookingJson(UUID sessionId, String reason) {

        try {
            return objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "sessionId", sessionId,
                            "reason", reason,
                            "period", "MORNING"
                    )
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not create test booking request."
            );
        }
    }

    private org.springframework.test.web.servlet.request
            .RequestPostProcessor customerJwt(UUID userId) {

        return jwt()
                .jwt(token -> token
                        .subject(userId.toString())
                        .claim("role", "CUSTOMER")
                        .claim("email", "api-test@example.test")
                )
                .authorities(
                        new SimpleGrantedAuthority("ROLE_CUSTOMER")
                );
    }

    private org.springframework.test.web.servlet.request
            .RequestPostProcessor doctorJwt(UUID userId) {

        return jwt()
                .jwt(token -> token
                        .subject(userId.toString())
                        .claim("role", "DOCTOR")
                        .claim("email", "api-doctor@example.test")
                )
                .authorities(
                        new SimpleGrantedAuthority("ROLE_DOCTOR")
                );
    }

    private record TestFixture(
            UUID patientUserId,
            UUID patientId,
            UUID secondPatientUserId,
            UUID sessionId
    ) {
    }
}
