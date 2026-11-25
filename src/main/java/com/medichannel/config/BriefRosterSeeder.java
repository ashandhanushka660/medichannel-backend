package com.medichannel.config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.medichannel.dto.doctor.CreateDoctorRequest;
import com.medichannel.dto.room.RoomRequest;
import com.medichannel.dto.session.DoctorSessionRequest;
import com.medichannel.dto.specialisation.SpecialisationRequest;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Room;
import com.medichannel.entity.Specialisation;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.RoomType;
import com.medichannel.enums.SessionStatus;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.service.DoctorService;
import com.medichannel.service.DoctorSessionService;
import com.medichannel.service.RoomService;
import com.medichannel.service.SpecialisationService;

/**
 * Brings the production roster into line with the assignment brief: 40
 * visiting doctors in 15 specialisations and more than 120 channelling
 * sessions every week.
 *
 * This runner is deliberately separate from DemoDataSeeder. It does not
 * create patients, notices or historical appointments, and it never cancels
 * existing sessions. Enable it with APP_BRIEF_ROSTER_SEED=true for one
 * controlled deployment; it is idempotent and safe to run again.
 */
@Component
public class BriefRosterSeeder implements ApplicationRunner {

    private static final Logger logger =
            LoggerFactory.getLogger(BriefRosterSeeder.class);

    private static final String EMAIL_DOMAIN = "medichannel.demo";
    private static final int TARGET_DOCTORS = 40;
    private static final int FIRST_NEW_DOCTOR_INDEX = 17;
    private static final int BASE_SESSIONS_PER_DOCTOR_PER_WEEK = 3;
    private static final int DOCTORS_WITH_FOUR_SESSIONS = 12;
    private static final int TARGET_SESSIONS_PER_WEEK =
            TARGET_DOCTORS * BASE_SESSIONS_PER_DOCTOR_PER_WEEK
                    + DOCTORS_WITH_FOUR_SESSIONS;

    private static final String[] SPECIALISATIONS = {
            "Cardiology",
            "Neurology",
            "Paediatrics",
            "Dermatology",
            "Orthopaedics",
            "General Medicine",
            "Obstetrics & Gynaecology",
            "ENT Surgery",
            "Ophthalmology",
            "General Surgery",
            "Psychiatry",
            "Urology",
            "Endocrinology",
            "Gastroenterology",
            "Rheumatology"
    };

    private static final String[] SPECIALISATION_DESCRIPTIONS = {
            "Heart and blood vessel conditions.",
            "Brain, spine and nervous system conditions.",
            "Medical care for infants, children and adolescents.",
            "Skin, hair and nail conditions.",
            "Bones, joints, muscles and sports injuries.",
            "General adult health and preventive care.",
            "Pregnancy, childbirth and women's health.",
            "Ear, nose and throat conditions.",
            "Eye care and vision conditions.",
            "Surgical treatment of general conditions.",
            "Mental health and behavioural conditions.",
            "Urinary tract and kidney-related surgical care.",
            "Hormonal and metabolic conditions.",
            "Digestive system conditions.",
            "Joint, tendon and autoimmune conditions."
    };

    private static final String[][] ROOMS = {
            {"R01", "Consultation Room 1", "Ground floor, wing A"},
            {"R02", "Consultation Room 2", "Ground floor, wing A"},
            {"R03", "Consultation Room 3", "Ground floor, wing A"},
            {"R04", "Consultation Room 4", "Ground floor, wing B"},
            {"R05", "Consultation Room 5", "Ground floor, wing B"},
            {"R06", "Consultation Room 6", "First floor, wing A"},
            {"R07", "Consultation Room 7", "First floor, wing A"},
            {"R08", "Consultation Room 8", "First floor, wing B"},
            {"R09", "Consultation Room 10", "Second floor"},
            {"R10", "Consultation Room 11", "Second floor"}
    };

    // Indices 17-39 continue DemoDataSeeder's deterministic naming and
    // registration numbers, so existing accounts are reused rather than
    // duplicated. The new specialisation indices bring the total to 15.
    private static final String[][] NEW_DOCTORS = {
            {"Gayani", "Alwis", "MBBS, MS (Ophthalmology)", "8"},
            {"Chamila", "Rajapaksa", "MBBS, MS (Ophthalmology)", "8"},
            {"Thusara", "Vidanage", "MBBS, MS (General Surgery)", "9"},
            {"Anushka", "Liyanage", "MBBS, MS (General Surgery)", "9"},
            {"Mithun", "Perera", "MBBS, MD (Psychiatry)", "10"},
            {"Piumi", "Rathnayake", "MBBS, MD (Psychiatry)", "10"},
            {"Sampath", "Sooriyaarachchi", "MBBS, MS (Urology)", "11"},
            {"Dulan", "Amarasinghe", "MBBS, MS (Urology)", "11"},
            {"Nayomi", "Jayasinghe", "MBBS, MD (Endocrinology)", "12"},
            {"Ranjith", "Wickramasinghe", "MBBS, MD (Endocrinology)", "12"},
            {"Supuni", "Gamage", "MBBS, MD (Gastroenterology)", "13"},
            {"Akila", "Deshapriya", "MBBS, MD (Gastroenterology)", "13"},
            {"Thilini", "Weerasekara", "MBBS, MD (Rheumatology)", "14"},
            {"Sandun", "Herath", "MBBS, MD (Rheumatology)", "14"},
            {"Madhavi", "Gunasekara", "MBBS, MD (Cardiology)", "0"},
            {"Charith", "Piyadasa", "MBBS, MD (Neurology)", "1"},
            {"Damayanthi", "Senanayake", "MBBS, MD (Paediatrics)", "2"},
            {"Prasanna", "Jayawardena", "MBBS, MD (Dermatology)", "3"},
            {"Ruwanthi", "Lokuge", "MBBS, MS (Orthopaedics)", "4"},
            {"Bimal", "Fernando", "MBBS, MD (General Medicine)", "5"},
            {"Hashini", "Premaratne", "MBBS, MS (Obstetrics & Gynaecology)", "6"},
            {"Navod", "Chandrasena", "MBBS, MS (ENT)", "7"},
            {"Ishani", "Gallage", "MBBS, MD (General Medicine)", "5"}
    };

    private final UserRepository userRepository;
    private final DoctorRepository doctorRepository;
    private final DoctorSessionRepository doctorSessionRepository;
    private final RoomRepository roomRepository;
    private final SpecialisationRepository specialisationRepository;
    private final SpecialisationService specialisationService;
    private final RoomService roomService;
    private final DoctorService doctorService;
    private final DoctorSessionService doctorSessionService;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    private final boolean enabled;
    private final int publishedWeeks;
    private final String demoPassword;

    public BriefRosterSeeder(
            UserRepository userRepository,
            DoctorRepository doctorRepository,
            DoctorSessionRepository doctorSessionRepository,
            RoomRepository roomRepository,
            SpecialisationRepository specialisationRepository,
            SpecialisationService specialisationService,
            RoomService roomService,
            DoctorService doctorService,
            DoctorSessionService doctorSessionService,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate,
            Clock clock,
            @Value("${APP_BRIEF_ROSTER_SEED:false}") boolean enabled,
            @Value("${APP_BRIEF_ROSTER_WEEKS:2}") int publishedWeeks,
            @Value("${APP_DEMO_PASSWORD:}") String demoPassword
    ) {
        this.userRepository = userRepository;
        this.doctorRepository = doctorRepository;
        this.doctorSessionRepository = doctorSessionRepository;
        this.roomRepository = roomRepository;
        this.specialisationRepository = specialisationRepository;
        this.specialisationService = specialisationService;
        this.roomService = roomService;
        this.doctorService = doctorService;
        this.doctorSessionService = doctorSessionService;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.enabled = enabled;
        this.publishedWeeks = publishedWeeks;
        this.demoPassword = demoPassword;
    }

    // No outer transaction: each service call commits its own work so a long
    // roster build cannot hold a pooled database connection open for minutes.
    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            logger.info(
                    "Brief roster seeding skipped: "
                            + "APP_BRIEF_ROSTER_SEED is not true."
            );
            return;
        }

        validatePassword();

        List<Specialisation> specialisations = seedSpecialisations();
        List<Room> rooms = seedRooms();
        List<Doctor> doctors = seedDoctors(specialisations);
        SeedingResult result = seedSessions(doctors, rooms);

        logger.info(
                "Brief roster seeding finished: doctors={}, "
                        + "specialisations={}, sessionsCreated={}, "
                        + "duplicatesSkipped={}, pastSlotsSkipped={}, "
                        + "currentWeekSessions={}, publishedThrough={}",
                doctors.size(),
                specialisations.size(),
                result.sessionsCreated(),
                result.duplicatesSkipped(),
                result.pastSlotsSkipped(),
                countCurrentWeekSessions(),
                LocalDate.now(clock)
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusDays(publishedWeeks * 7L - 1L)
        );
    }

    private void validatePassword() {
        if (demoPassword == null || demoPassword.isBlank()) {
            throw new IllegalStateException(
                    "APP_BRIEF_ROSTER_SEED is enabled but "
                            + "APP_DEMO_PASSWORD is not set."
            );
        }

        int passwordBytes = demoPassword
                .getBytes(StandardCharsets.UTF_8).length;

        if (demoPassword.length() < 8 || passwordBytes > 72) {
            throw new IllegalStateException(
                    "APP_DEMO_PASSWORD must contain at least 8 characters "
                            + "and must not exceed 72 UTF-8 bytes."
            );
        }

        if (!demoPassword.matches(
                "(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+"
        )) {
            throw new IllegalStateException(
                    "APP_DEMO_PASSWORD must contain a letter, a number "
                            + "and a symbol."
            );
        }
    }

    private List<Specialisation> seedSpecialisations() {
        List<Specialisation> result = new ArrayList<>();

        for (int index = 0; index < SPECIALISATIONS.length; index += 1) {
            String name = SPECIALISATIONS[index];
            String description = SPECIALISATION_DESCRIPTIONS[index];
            Specialisation specialisation = specialisationRepository
                    .findByName(name)
                    .orElseGet(() -> {
                        specialisationService.create(
                                new SpecialisationRequest(
                                        name,
                                        description
                                )
                        );
                        return specialisationRepository.findByName(name)
                                .orElseThrow();
                    });
            result.add(specialisation);
        }

        return result;
    }

    private List<Room> seedRooms() {
        List<Room> result = new ArrayList<>();

        for (String[] details : ROOMS) {
            Room room = roomRepository.findByRoomNumber(details[0])
                    .orElseGet(() -> {
                        roomService.create(new RoomRequest(
                                details[0],
                                details[1],
                                details[2],
                                RoomType.CONSULTATION,
                                1
                        ));
                        return roomRepository.findByRoomNumber(details[0])
                                .orElseThrow();
                    });
            result.add(room);
        }

        return result;
    }

    private List<Doctor> seedDoctors(List<Specialisation> specialisations) {
        long availableDoctors = doctorRepository.findPublicDirectory().size();

        for (int index = FIRST_NEW_DOCTOR_INDEX;
             index < FIRST_NEW_DOCTOR_INDEX + NEW_DOCTORS.length
                     && availableDoctors < TARGET_DOCTORS;
             index += 1) {
            String[] details = NEW_DOCTORS[index - FIRST_NEW_DOCTOR_INDEX];
            if (createDoctor(index, details, specialisations)) {
                availableDoctors += 1;
                logger.info(
                        "Brief roster doctor created: {}",
                        emailFor(details[0], details[1])
                );
            }
        }

        int reliefIndex = 1;
        while (availableDoctors < TARGET_DOCTORS) {
            String email = String.format(
                    Locale.ROOT,
                    "brief-relief-%02d@%s",
                    reliefIndex,
                    EMAIL_DOMAIN
            );
            String registrationNumber = String.format(
                    Locale.ROOT,
                    "SLMC-%05d",
                    91000 + reliefIndex
            );
            String[] details = {
                    "Relief",
                    "Doctor " + reliefIndex,
                    "MBBS, MD (General Medicine)",
                    String.valueOf(reliefIndex % specialisations.size())
            };

            if (createDoctor(registrationNumber, email, details,
                    specialisations)) {
                availableDoctors += 1;
                logger.info("Brief roster relief doctor created: {}", email);
            }
            reliefIndex += 1;
        }

        return doctorRepository.findPublicDirectory().stream()
                .sorted(Comparator.comparing(Doctor::getRegistrationNumber))
                .limit(TARGET_DOCTORS)
                .toList();
    }

    private boolean createDoctor(
            int index,
            String[] details,
            List<Specialisation> specialisations
    ) {
        String registrationNumber = String.format(
                Locale.ROOT,
                "SLMC-%05d",
                10001 + index
        );
        String email = emailFor(details[0], details[1]);
        return createDoctor(registrationNumber, email, details,
                specialisations);
    }

    private boolean createDoctor(
            String registrationNumber,
            String email,
            String[] details,
            List<Specialisation> specialisations
    ) {
        if (userRepository.existsByEmail(email)
                || doctorRepository.existsByRegistrationNumber(
                        registrationNumber
                )) {
            return false;
        }

        Specialisation specialisation = specialisations.get(
                Integer.parseInt(details[3])
        );

        doctorService.createDoctor(new CreateDoctorRequest(
                email,
                demoPassword,
                demoPassword,
                details[0],
                details[1],
                registrationNumber,
                details[2],
                specialisation.getId(),
                consultationFee(registrationNumber)
        ));
        return true;
    }

    private BigDecimal consultationFee(String registrationNumber) {
        int suffix;
        try {
            suffix = Integer.parseInt(
                    registrationNumber.substring(registrationNumber.length() - 3)
            );
        } catch (RuntimeException exception) {
            suffix = 0;
        }
        return new BigDecimal("2500.00")
                .add(BigDecimal.valueOf((suffix % 20) * 100L));
    }

    private SeedingResult seedSessions(
            List<Doctor> doctors,
            List<Room> rooms
    ) {
        List<Room> consultationRooms = rooms.stream()
                .filter(room -> room.isActive()
                        && room.getRoomType() != null
                        && RoomType.CONSULTATION == room.getRoomType())
                .sorted(Comparator.comparing(Room::getRoomNumber))
                .toList();

        if (consultationRooms.isEmpty()) {
            throw new IllegalStateException(
                    "At least one active consultation room is required "
                            + "to publish the weekly roster."
            );
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate weekStart = today.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        );
        LocalDateTime now = LocalDateTime.now(clock);

        int created = 0;
        int duplicates = 0;
        int pastSlots = 0;

        for (int week = 0; week < publishedWeeks; week += 1) {
            int doctorIndex = 0;
            for (Doctor doctor : doctors) {
                for (PlannedSession planned : weeklyPlan(doctorIndex)) {
                    LocalDate date = weekStart.plusDays(
                            week * 7L + planned.dayOffset()
                    );
                    LocalTime startTime = planned.startTime();
                    LocalTime endTime = planned.endTime();

                    if (!date.atTime(endTime).isAfter(now)) {
                        pastSlots += 1;
                        continue;
                    }

                    if (alreadyPublished(
                            doctor.getId(),
                            date,
                            startTime,
                            endTime
                    )) {
                        duplicates += 1;
                        continue;
                    }

                    Room room = consultationRooms.stream()
                            .filter(candidate -> isRoomAvailable(
                                    candidate, date, startTime, endTime
                            ))
                            .findFirst()
                            .orElse(null);
                    boolean online = room == null;

                    doctorSessionService.createSession(
                            new DoctorSessionRequest(
                                    doctor.getId(),
                                    online ? null : room.getId(),
                                    date,
                                    startTime,
                                    endTime,
                                    online
                                            ? ConsultationType.ONLINE
                                            : ConsultationType.PHYSICAL,
                                    ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION,
                                    ClinicSchedule.MINUTES_PER_PATIENT,
                                    consultationFee(
                                            doctor.getRegistrationNumber()
                                    )
                            )
                    );
                    created += 1;
                }
                doctorIndex += 1;
            }
        }

        return new SeedingResult(created, duplicates, pastSlots);
    }

    /**
     * The same 132-session weekly pattern used by the production roster
     * migration: three spread-out days per doctor, an alternating first
     * window, and one opposite-window session for the first twelve doctors.
     */
    private List<PlannedSession> weeklyPlan(int doctorIndex) {
        List<PlannedSession> plan = new ArrayList<>(4);
        int firstDay = doctorIndex % 7;
        int[] days = {
                firstDay,
                (firstDay + 2) % 7,
                (firstDay + 4) % 7
        };
        boolean firstWindowIsMorning = doctorIndex % 2 == 0;

        for (int index = 0; index < days.length; index += 1) {
            boolean morning = index % 2 == 0
                    ? firstWindowIsMorning
                    : !firstWindowIsMorning;
            plan.add(plannedSession(days[index], morning));
        }

        if (doctorIndex < DOCTORS_WITH_FOUR_SESSIONS) {
            plan.add(plannedSession(firstDay, !firstWindowIsMorning));
        }

        return plan;
    }

    private PlannedSession plannedSession(int dayOffset, boolean morning) {
        return morning
                ? new PlannedSession(
                        dayOffset,
                        ClinicSchedule.MORNING_START,
                        ClinicSchedule.MORNING_END
                )
                : new PlannedSession(
                        dayOffset,
                        ClinicSchedule.EVENING_START,
                        ClinicSchedule.EVENING_END
                );
    }

    private record PlannedSession(
            int dayOffset,
            LocalTime startTime,
            LocalTime endTime
    ) {
    }

    private boolean alreadyPublished(
            java.util.UUID doctorId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime
    ) {
        return doctorSessionRepository
                .findByDoctorIdAndSessionDateAndStatus(
                        doctorId,
                        date,
                        SessionStatus.OPEN
                )
                .stream()
                .anyMatch(session ->
                        startTime.equals(session.getStartTime())
                                && endTime.equals(session.getEndTime())
                );
    }

    private boolean isRoomAvailable(
            Room room,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime
    ) {
        return doctorSessionRepository
                .findByRoom_IdAndSessionDate(room.getId(), date)
                .stream()
                .noneMatch(session ->
                        session.getStatus() != SessionStatus.CANCELLED
                                && session.getStartTime().isBefore(endTime)
                                && session.getEndTime().isAfter(startTime)
                );
    }

    private long countCurrentWeekSessions() {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        );
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM doctor_sessions
                WHERE session_date BETWEEN ? AND ?
                  AND status <> 'CANCELLED'
                """,
                Long.class,
                java.sql.Date.valueOf(from),
                java.sql.Date.valueOf(from.plusDays(6))
        );
        return count == null ? 0L : count;
    }

    private String emailFor(String firstName, String lastName) {
        return (firstName + "." + lastName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.]", "")
                + "@" + EMAIL_DOMAIN;
    }

    private record SeedingResult(
            int sessionsCreated,
            int duplicatesSkipped,
            int pastSlotsSkipped
    ) {
    }
}
