package com.medichannel.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import jakarta.persistence.EntityManagerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.Patient;
import com.medichannel.entity.Role;
import com.medichannel.entity.Room;
import com.medichannel.entity.Specialisation;
import com.medichannel.entity.User;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.RoomType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
@Transactional
class PublicDoctorDirectoryIntegrationTests {

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
    private RoomRepository roomRepository;

    @Autowired
    private DoctorSessionRepository doctorSessionRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private PatientDataProtectionService dataProtectionService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void guestCanSeeDoctorsRoomsHoursFeesAndLiveQueue() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");

        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Role customerRole = roleRepository.findByName("CUSTOMER").orElseThrow();

        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Directory Test " + unique,
                        "Temporary public directory test"
                )
        );

        Doctor availableDoctor = createDoctor(
                doctorRole,
                specialisation,
                "Live",
                "Doctor",
                "DIR-LIVE-" + unique,
                new BigDecimal("2500.00"),
                unique
        );

        Doctor unavailableDoctor = createDoctor(
                doctorRole,
                specialisation,
                "Off",
                "Doctor",
                "DIR-OFF-" + unique,
                new BigDecimal("1800.00"),
                unique
        );
        unavailableDoctor.updateAvailability(false);
        doctorRepository.saveAndFlush(unavailableDoctor);

        Room room = roomRepository.saveAndFlush(
                new Room(
                        "DIR-" + unique.substring(0, 12),
                        "Directory Test Room",
                        "Local Test Hospital",
                        RoomType.CONSULTATION,
                        4
                )
        );

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        var session = new com.medichannel.entity.DoctorSession(
                availableDoctor,
                room,
                today,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ConsultationType.PHYSICAL,
                4
        );
        session.updateSchedulingDetails(
                ClinicSchedule.MINUTES_PER_PATIENT,
                new BigDecimal("2500.00")
        );
        session.open();
        session = doctorSessionRepository.saveAndFlush(session);

        User patientUser = new User(
                customerRole,
                "directory.patient." + unique + "@example.test",
                "test-password-hash"
        );
        patientUser.activateAfterVerification();
        patientUser = userRepository.saveAndFlush(patientUser);

        Patient patient = patientRepository.saveAndFlush(
                new Patient(
                        patientUser,
                        dataProtectionService.encrypt("Directory"),
                        dataProtectionService.encrypt("Patient"),
                        dataProtectionService.encrypt("0771234567"),
                        null,
                        dataProtectionService.encrypt("2000-01-01"),
                        dataProtectionService.encrypt("OTHER")
                )
        );

        Appointment appointment = new Appointment(
                session,
                patient,
                dataProtectionService.encrypt("Follow-up")
        );
        appointment.markArrived(Instant.now());
        appointment.startConsultation(Instant.now());
        appointmentRepository.saveAndFlush(appointment);

        session.start();
        doctorSessionRepository.saveAndFlush(session);

        String body = mockMvc.perform(get("/api/v1/public/doctors"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode doctors = objectMapper.readTree(body);
        JsonNode live = findDoctor(doctors, availableDoctor.getId());
        JsonNode off = findDoctor(doctors, unavailableDoctor.getId());

        assertThat(live).isNotNull();
        assertThat(live.get("doctorName").asText()).isEqualTo("Dr. Live Doctor");
        assertThat(live.get("specialisation").asText())
                .isEqualTo(specialisation.getName());
        assertThat(live.get("roomNumber").asText())
                .isEqualTo(room.getRoomNumber());
        assertThat(new BigDecimal(live.get("consultationFee").asText()))
                .isEqualByComparingTo(new BigDecimal("2500.00"));
        assertThat(live.get("available").asBoolean()).isTrue();
        assertThat(live.get("defaultStartTime").asText()).isEqualTo("07:00:00");
        assertThat(live.get("defaultEndTime").asText()).isEqualTo("16:00:00");
        assertThat(live.get("defaultHours").asInt()).isEqualTo(8);
        assertThat(live.get("minutesPerPatient").asInt())
                .isEqualTo(ClinicSchedule.MINUTES_PER_PATIENT);
        assertThat(live.get("consultationType").asText()).isEqualTo("PHYSICAL");
        assertThat(live.get("sessionStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(live.get("nowServing").asInt())
                .isEqualTo(appointment.getAppointmentNumber());
        assertThat(live.get("nextNumber").asInt()).isEqualTo(2);
        assertThat(live.get("remainingAppointments").asInt()).isEqualTo(3);
        assertThat(live.get("maxAppointments").asInt()).isEqualTo(4);

        assertThat(off).isNotNull();
        assertThat(off.get("available").asBoolean()).isFalse();
        assertThat(off.get("sessionId").isNull()).isTrue();
        assertThat(off.get("defaultHours").asInt()).isEqualTo(8);
    }

    @Test
    void sessionsCanBeFilteredByAnInclusiveWeekRange() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");

        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Range Test " + unique,
                        "Temporary public session range test"
                )
        );
        Doctor doctor = createDoctor(
                doctorRole,
                specialisation,
                "Range",
                "Doctor",
                "RANGE-" + unique,
                new BigDecimal("2500.00"),
                unique
        );
        Room room = roomRepository.saveAndFlush(
                new Room(
                        "RANGE-" + unique.substring(0, 10),
                        "Range Test Room",
                        "Local Test Hospital",
                        RoomType.CONSULTATION,
                        4
                )
        );

        LocalDate weekStart = LocalDate
                .now(ZoneId.of("Asia/Colombo"))
                .plusDays(1);
        saveOpenSession(doctor, room, weekStart);
        saveOpenSession(doctor, room, weekStart.plusDays(6));
        saveOpenSession(doctor, room, weekStart.plusDays(7));
        // The dashboard's last option reaches six months ahead.
        saveOpenSession(doctor, room, weekStart.plusDays(181));

        String weekBody = mockMvc.perform(get("/api/v1/sessions")
                        .param("from", weekStart.toString())
                        .param("to", weekStart.plusDays(6).toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode week = objectMapper.readTree(weekBody);
        assertThat(week.size()).isEqualTo(2);
        assertThat(week.get(0).get("sessionDate").asText())
                .isEqualTo(weekStart.toString());
        assertThat(week.get(1).get("sessionDate").asText())
                .isEqualTo(weekStart.plusDays(6).toString());

        // The existing exact-date filter remains available for other callers.
        String dayBody = mockMvc.perform(get("/api/v1/sessions")
                        .param("date", weekStart.plusDays(7).toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode day = objectMapper.readTree(dayBody);
        assertThat(day.size()).isEqualTo(1);
        assertThat(day.get(0).get("sessionDate").asText())
                .isEqualTo(weekStart.plusDays(7).toString());

        String sixMonthBody = mockMvc.perform(get("/api/v1/sessions")
                        .param("from", weekStart.toString())
                        .param("to", weekStart.plusDays(181).toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode sixMonths = objectMapper.readTree(sixMonthBody);
        assertThat(sixMonths.size()).isEqualTo(4);
        assertThat(sixMonths.get(3).get("sessionDate").asText())
                .isEqualTo(weekStart.plusDays(181).toString());
    }

    @Test
    void weekCatalogueUsesOneCountQueryForTheWholePage() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");

        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Batched Range Test " + unique,
                        "Temporary batched session range test"
                )
        );
        Doctor doctor = createDoctor(
                doctorRole,
                specialisation,
                "Batched",
                "Range",
                "BATCH-RANGE-" + unique,
                new BigDecimal("2500.00"),
                unique
        );
        Room room = roomRepository.saveAndFlush(
                new Room(
                        "BATCH-" + unique.substring(0, 10),
                        "Batched Range Room",
                        "Local Test Hospital",
                        RoomType.CONSULTATION,
                        4
                )
        );

        LocalDate start = LocalDate
                .now(ZoneId.of("Asia/Colombo"))
                .plusDays(1);
        for (int day = 0; day < 7; day++) {
            saveOpenSession(doctor, room, start.plusDays(day));
        }

        Statistics statistics = statistics();

        statistics.clear();
        mockMvc.perform(get("/api/v1/sessions")
                        .param("date", start.toString()))
                .andExpect(status().isOk());
        long singleDayStatements = statistics.getPrepareStatementCount();

        statistics.clear();
        mockMvc.perform(get("/api/v1/sessions")
                        .param("from", start.toString())
                        .param("to", start.plusDays(6).toString()))
                .andExpect(status().isOk());
        long weekStatements = statistics.getPrepareStatementCount();

        // Before batching this grew by one count query per extra session.
        assertThat(weekStatements - singleDayStatements)
                .as("week view must not add a count query per session")
                .isLessThanOrEqualTo(2);
    }

    @Test
    void directoryUsesBoundedQueriesForTheWholeRoster() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");

        Role doctorRole = roleRepository.findByName("DOCTOR").orElseThrow();
        Specialisation specialisation = specialisationRepository.saveAndFlush(
                new Specialisation(
                        "Batched Directory Test " + unique,
                        "Temporary batched directory test"
                )
        );

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        for (int doctorIndex = 0; doctorIndex < 6; doctorIndex++) {
            Doctor doctor = createDoctor(
                    doctorRole,
                    specialisation,
                    "Batched" + doctorIndex,
                    "Doctor",
                    "BATCH-DIR-" + doctorIndex + "-" + unique,
                    new BigDecimal("2000.00"),
                    unique
            );
            saveOpenOnlineSession(doctor, today);
        }

        Statistics statistics = statistics();

        statistics.clear();
        String body = mockMvc.perform(get("/api/v1/public/doctors"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long statements = statistics.getPrepareStatementCount();

        JsonNode doctors = objectMapper.readTree(body);
        assertThat(doctors.size()).isGreaterThanOrEqualTo(6);
        // One doctor query, one session query and two grouped queue queries.
        assertThat(statements)
                .as("directory query count must not scale with roster size")
                .isLessThanOrEqualTo(12);
    }

    private Statistics statistics() {
        return entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();
    }

    private void saveOpenSession(Doctor doctor, Room room, LocalDate date) {
        var session = new com.medichannel.entity.DoctorSession(
                doctor,
                room,
                date,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ConsultationType.PHYSICAL,
                4
        );
        session.updateSchedulingDetails(
                ClinicSchedule.MINUTES_PER_PATIENT,
                new BigDecimal("2500.00")
        );
        session.open();
        doctorSessionRepository.saveAndFlush(session);
    }

    private void saveOpenOnlineSession(Doctor doctor, LocalDate date) {
        var session = new com.medichannel.entity.DoctorSession(
                doctor,
                null,
                date,
                ClinicSchedule.MORNING_START,
                ClinicSchedule.MORNING_END,
                ConsultationType.ONLINE,
                4
        );
        session.updateSchedulingDetails(
                ClinicSchedule.MINUTES_PER_PATIENT,
                new BigDecimal("2500.00")
        );
        session.open();
        doctorSessionRepository.saveAndFlush(session);
    }

    private Doctor createDoctor(
            Role role,
            Specialisation specialisation,
            String firstName,
            String lastName,
            String registrationNumber,
            BigDecimal fee,
            String unique
    ) {
        User user = new User(
                role,
                "directory." + registrationNumber.toLowerCase() + "@example.test",
                "test-password-hash"
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
                        "Temporary public directory test",
                        fee
                )
        );
    }

    private JsonNode findDoctor(JsonNode doctors, UUID doctorId) {
        for (JsonNode doctor : doctors) {
            if (doctorId.toString().equals(doctor.get("doctorId").asText())) {
                return doctor;
            }
        }
        return null;
    }
}
