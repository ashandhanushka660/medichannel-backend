package com.medichannel.config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.doctor.CreateDoctorRequest;
import com.medichannel.dto.room.RoomRequest;
import com.medichannel.dto.session.DoctorSessionRequest;
import com.medichannel.dto.session.DoctorSessionResponse;
import com.medichannel.dto.specialisation.SpecialisationRequest;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Notice;
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
import com.medichannel.repository.NoticeRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.RoomRepository;
import com.medichannel.repository.SpecialisationRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.PatientDataProtectionService;
import com.medichannel.service.DoctorService;
import com.medichannel.service.DoctorSessionService;
import com.medichannel.service.RoomService;
import com.medichannel.service.SpecialisationService;

/**
 * Optional demo data for a demonstration environment.
 *
 * Disabled unless APP_DEMO_SEED=true. The password never lives in git:
 * it is read from APP_DEMO_PASSWORD and hashed with the application's
 * BCrypt encoder at startup. The runner is idempotent and only logs the
 * accounts it creates, never the password or its hash.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger logger =
            LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final int MINIMUM_PASSWORD_LENGTH = 8;
    private static final int MAXIMUM_BCRYPT_PASSWORD_BYTES = 72;

    private static final String EMAIL_DOMAIN = "medichannel.demo";

    private static final String[] SPECIALISATIONS = {
            "Cardiology",
            "Neurology",
            "Paediatrics",
            "Dermatology",
            "Orthopaedics",
            "General Medicine",
            "Obstetrics & Gynaecology",
            "ENT Surgery"
    };

    private static final String[] SPECIALISATION_DESCRIPTIONS = {
            "Heart and blood vessel conditions.",
            "Brain, spine and nervous system conditions.",
            "Medical care for infants, children and adolescents.",
            "Skin, hair and nail conditions.",
            "Bones, joints, muscles and sports injuries.",
            "General adult health and preventive care.",
            "Pregnancy, childbirth and women's health.",
            "Ear, nose and throat conditions."
    };

    private static final String[][] DOCTORS = {
            {"Nadeesha", "Wijesinghe", "MBBS, MD (Cardiology)", "0"},
            {"Amaya", "Fernando", "MBBS, MD (Cardiology)", "0"},
            {"Kasun", "Perera", "MBBS, MD (Neurology)", "1"},
            {"Dilani", "Jayawardena", "MBBS, MD (Paediatrics)", "2"},
            {"Ruwan", "Silva", "MBBS, MD (Paediatrics)", "2"},
            {"Ishara", "Bandara", "MBBS, MD (Dermatology)", "3"},
            {"Tharindu", "Gunawardena", "MBBS, MS (Orthopaedics)", "4"},
            {"Malini", "Rathnayake", "MBBS, MD (General Medicine)", "5"},
            {"Sanjeewa", "Dissanayake", "MBBS, MD (General Medicine)", "5"},
            {"Hasini", "Karunaratne", "MBBS, MS (Obstetrics & Gynaecology)", "6"},
            {"Nuwan", "Herath", "MBBS, MS (ENT)", "7"},
            {"Chamari", "Weerasinghe", "MBBS, MD (Dermatology)", "3"},
            {"Nimali", "Fernando", "MBBS, MD (Cardiology)", "0"},
            {"Pradeep", "Kumara", "MBBS, MD (Neurology)", "1"},
            {"Shashini", "Maduranga", "MBBS, MD (Paediatrics)", "2"},
            {"Eranda", "Weeraratne", "MBBS, MD (Dermatology)", "3"},
            {"Lahiru", "Dharmasena", "MBBS, MS (Orthopaedics)", "4"},
            {"Gayani", "Alwis", "MBBS, MD (General Medicine)", "5"},
            {"Chamila", "Rajapaksa", "MBBS, MS (Obstetrics & Gynaecology)", "6"},
            {"Thusara", "Vidanage", "MBBS, MS (ENT)", "7"},
            {"Anushka", "Liyanage", "MBBS, MD (Cardiology)", "0"},
            {"Mithun", "Perera", "MBBS, MD (Neurology)", "1"},
            {"Piumi", "Rathnayake", "MBBS, MD (Paediatrics)", "2"},
            {"Sampath", "Sooriyaarachchi", "MBBS, MD (Dermatology)", "3"},
            {"Dulan", "Amarasinghe", "MBBS, MS (Orthopaedics)", "4"},
            {"Nayomi", "Jayasinghe", "MBBS, MD (General Medicine)", "5"},
            {"Ranjith", "Wickramasinghe", "MBBS, MS (Obstetrics & Gynaecology)", "6"},
            {"Supuni", "Gamage", "MBBS, MS (ENT)", "7"},
            {"Akila", "Deshapriya", "MBBS, MD (Cardiology)", "0"},
            {"Thilini", "Weerasekara", "MBBS, MD (Neurology)", "1"},
            {"Sandun", "Herath", "MBBS, MD (Paediatrics)", "2"},
            {"Madhavi", "Gunasekara", "MBBS, MD (Dermatology)", "3"},
            {"Charith", "Piyadasa", "MBBS, MS (Orthopaedics)", "4"},
            {"Damayanthi", "Senanayake", "MBBS, MD (General Medicine)", "5"},
            {"Prasanna", "Jayawardena", "MBBS, MS (Obstetrics & Gynaecology)", "6"},
            {"Ruwanthi", "Lokuge", "MBBS, MS (ENT)", "7"},
            {"Bimal", "Fernando", "MBBS, MD (Cardiology)", "0"},
            {"Hashini", "Premaratne", "MBBS, MD (Neurology)", "1"},
            {"Navod", "Chandrasena", "MBBS, MD (Paediatrics)", "2"},
            {"Ishani", "Gallage", "MBBS, MD (Dermatology)", "3"}
    };

    // Two decimal fees between LKR 2,500 and 4,500 for all 40 doctors.
    private static final BigDecimal[] FEES = {
            new BigDecimal("4500.00"),
            new BigDecimal("4200.00"),
            new BigDecimal("4000.00"),
            new BigDecimal("3500.00"),
            new BigDecimal("3500.00"),
            new BigDecimal("3000.00"),
            new BigDecimal("4500.00"),
            new BigDecimal("2500.00"),
            new BigDecimal("2500.00"),
            new BigDecimal("4000.00"),
            new BigDecimal("3800.00"),
            new BigDecimal("3000.00"),
            new BigDecimal("4100.00"),
            new BigDecimal("3600.00"),
            new BigDecimal("3200.00"),
            new BigDecimal("2900.00"),
            new BigDecimal("4400.00"),
            new BigDecimal("2700.00"),
            new BigDecimal("3300.00"),
            new BigDecimal("3100.00"),
            new BigDecimal("4300.00"),
            new BigDecimal("3700.00"),
            new BigDecimal("3400.00"),
            new BigDecimal("2800.00"),
            new BigDecimal("4500.00"),
            new BigDecimal("2600.00"),
            new BigDecimal("3900.00"),
            new BigDecimal("3050.00"),
            new BigDecimal("4150.00"),
            new BigDecimal("3550.00"),
            new BigDecimal("3250.00"),
            new BigDecimal("2950.00"),
            new BigDecimal("4450.00"),
            new BigDecimal("2750.00"),
            new BigDecimal("3350.00"),
            new BigDecimal("3150.00"),
            new BigDecimal("4350.00"),
            new BigDecimal("3650.00"),
            new BigDecimal("3450.00"),
            new BigDecimal("2850.00")
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
            {"R09", "Procedure Room 1", "Second floor"},
            {"R10", "Consultation Room 10", "Second floor"}
    };

    private static final int TARGET_AVAILABLE_DOCTORS = 40;
    private static final int SESSIONS_PER_DOCTOR_PER_WEEK = 6;
    private static final int DOCTORS_PER_DAY = 20;
    // The public dashboard offers sessions up to six months ahead, week by
    // week, so the demo roster is published for the same horizon.
    private static final int PUBLISHED_WEEKS = 26;

    // 20 doctors x 3 days x 2 windows = 120 sessions per cohort, so the two
    // cohorts publish 240 sessions each week with 20 doctors on every day.
    private static final int[] MON_WED_FRI = {0, 2, 4};
    private static final int[] TUE_THU_SAT = {1, 3, 5};

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DoctorRepository doctorRepository;
    private final DoctorSessionRepository doctorSessionRepository;
    private final SpecialisationRepository specialisationRepository;
    private final RoomRepository roomRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final NoticeRepository noticeRepository;
    private final PatientDataProtectionService dataProtectionService;
    private final SpecialisationService specialisationService;
    private final RoomService roomService;
    private final DoctorService doctorService;
    private final DoctorSessionService doctorSessionService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    private final boolean enabled;
    private final String demoPassword;
    private final boolean demoConfigured;

    public DemoDataSeeder(
            UserRepository userRepository,
            RoleRepository roleRepository,
            DoctorRepository doctorRepository,
            DoctorSessionRepository doctorSessionRepository,
            SpecialisationRepository specialisationRepository,
            RoomRepository roomRepository,
            PatientRepository patientRepository,
            AppointmentRepository appointmentRepository,
            NoticeRepository noticeRepository,
            PatientDataProtectionService dataProtectionService,
            SpecialisationService specialisationService,
            RoomService roomService,
            DoctorService doctorService,
            DoctorSessionService doctorSessionService,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${APP_DEMO_SEED:false}") boolean enabled,
            @Value("${APP_DEMO_PASSWORD:}") String demoPassword
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.doctorRepository = doctorRepository;
        this.doctorSessionRepository = doctorSessionRepository;
        this.specialisationRepository = specialisationRepository;
        this.roomRepository = roomRepository;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.noticeRepository = noticeRepository;
        this.dataProtectionService = dataProtectionService;
        this.specialisationService = specialisationService;
        this.roomService = roomService;
        this.doctorService = doctorService;
        this.doctorSessionService = doctorSessionService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.enabled = enabled;
        this.demoPassword = demoPassword;
        this.demoConfigured = demoPassword != null
                && !demoPassword.isBlank();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            logger.info(
                    "Demo data seeding skipped: APP_DEMO_SEED is not true."
            );
            return;
        }

        validatePassword();

        logger.info("Demo data seeding started.");

        List<Specialisation> specialisations = seedSpecialisations();
        List<Room> rooms = seedRooms();

        List<Doctor> doctors = seedDoctors(specialisations);
        seedDemoAdmin();
        seedManager();

        cancelStalePublishedSessions();
        seedSessions(doctors, rooms);
        seedDemoPatientsAndHistory(doctors, rooms);
        seedNotices();

        logger.info(
                "Demo data seeding finished: {} doctors, {} rooms and "
                        + "six sessions per doctor each week are available.",
                doctors.size(),
                rooms.size()
        );
    }

    private void validatePassword() {
        if (!demoConfigured) {
            throw new IllegalStateException(
                    "APP_DEMO_SEED is enabled but APP_DEMO_PASSWORD is not set."
            );
        }

        int passwordBytes = demoPassword
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (demoPassword.length() < MINIMUM_PASSWORD_LENGTH
                || passwordBytes > MAXIMUM_BCRYPT_PASSWORD_BYTES) {
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

    /**
     * Creates fictional customer accounts and a past session containing
     * one appointment in every lifecycle status. This gives assessors
     * realistic data for every role and every status without touching
     * production data.
     */
    private void seedDemoPatientsAndHistory(
            List<Doctor> doctors,
            List<Room> rooms
    ) {
        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException(
                        "The required CUSTOMER role is not configured."
                ));

        String[][] people = {
                {"Nimal", "Perera", "199012345678", "Colombo 05"},
                {"Sanduni", "Fernando", "199223456789", "Kandy"},
                {"Ruwan", "Jayasinghe", "198834567890", "Galle"},
                {"Tharushi", "Mendis", "199545678901", "Negombo"},
                {"Kasun", "Wickramasinghe", "199356789012", "Matara"},
                {"Anjali", "Dissanayake", "199667890123", "Kurunegala"}
        };

        List<Patient> patients = new ArrayList<>();

        for (int index = 0; index < people.length; index++) {
            String[] person = people[index];
            String email = "patient" + (index + 1) + "@" + EMAIL_DOMAIN;

            User user = userRepository.findByEmail(email).orElse(null);

            if (user == null) {
                user = new User(
                        customerRole,
                        email,
                        passwordEncoder.encode(demoPassword)
                );
                user.activateByAdministrator();
                userRepository.saveAndFlush(user);
                logger.info("Demo patient created: {}", email);
            }

            final User owner = user;
            final int number = index;

            Patient patient = patientRepository.findByUserId(owner.getId())
                    .orElseGet(() -> patientRepository.saveAndFlush(
                            new Patient(
                                    owner,
                                    dataProtectionService.encrypt(
                                            person[0]
                                    ),
                                    dataProtectionService.encrypt(
                                            person[1]
                                    ),
                                    dataProtectionService.encrypt(
                                            "07712345" + (10 + number)
                                    ),
                                    dataProtectionService.createNicLookupHash(
                                            person[2]
                                    ),
                                    dataProtectionService.encrypt(person[2]),
                                    dataProtectionService.encrypt(person[3]),
                                    dataProtectionService.encrypt(
                                            "1990-01-0" + (number + 1)
                                    ),
                                    dataProtectionService.encrypt(
                                            number % 2 == 0
                                                    ? "MALE"
                                                    : "FEMALE"
                                    )
                            )
                    ));

            patients.add(patient);
        }

        if (appointmentRepository.count() > 0) {
            return;
        }

        Doctor doctor = doctors.get(0);
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);

        DoctorSession session = new DoctorSession(
                doctor,
                rooms.get(0),
                yesterday,
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                ConsultationType.PHYSICAL,
                12
        );
        session.open();
        doctorSessionRepository.saveAndFlush(session);

        Instant base = yesterday.atTime(9, 0)
                .atZone(TimeConfiguration.APPLICATION_ZONE)
                .toInstant();

        for (int index = 0; index < 6; index++) {
            Appointment appointment = new Appointment(
                    session,
                    patients.get(index % patients.size()),
                    dataProtectionService.encrypt(
                            "Demo consultation reason " + (index + 1)
                    )
            );
            appointment = appointmentRepository.saveAndFlush(appointment);

            switch (index) {
                case 1 -> appointment.markArrived(base.plusSeconds(600));
                case 2 -> {
                    appointment.markArrived(base.plusSeconds(1200));
                    appointment.startConsultation(base.plusSeconds(1500));
                }
                case 3 -> {
                    appointment.markArrived(base.plusSeconds(1800));
                    appointment.startConsultation(base.plusSeconds(2100));
                    appointment.complete(base.plusSeconds(2400));
                }
                case 4 -> appointment.markNoShow();
                case 5 -> appointment.cancelByStaff(base.plusSeconds(3000));
                default -> {
                    // BOOKED is the initial status.
                }
            }

            appointmentRepository.saveAndFlush(appointment);
        }

        logger.info(
                "Demo history seeded: one appointment in every status."
        );

        session.start();
        doctorSessionRepository.saveAndFlush(session);
    }

    private void seedNotices() {
        if (noticeRepository.count() > 0) {
            return;
        }

        User manager = userRepository.findByEmail("manager@" + EMAIL_DOMAIN)
                .orElse(null);

        if (manager == null) {
            return;
        }

        Notice welcome = new Notice(
                "Welcome to MediChannel",
                "Book a doctor, pay securely and join your consultation "
                        + "online. Reception opens 30 minutes before each "
                        + "session.",
                manager
        );
        welcome.publish();
        noticeRepository.save(welcome);

        Notice parking = new Notice(
                "Parking and accessibility",
                "Visitor parking is available on level B1. Wheelchair "
                        + "access is provided at the main entrance and all "
                        + "consultation rooms.",
                manager
        );
        parking.publish();
        noticeRepository.save(parking);

        logger.info("Demo notices seeded.");
    }

    private List<Specialisation> seedSpecialisations() {
        List<Specialisation> result = new ArrayList<>();

        for (int index = 0; index < SPECIALISATIONS.length; index++) {
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

                        return specialisationRepository
                                .findByName(name)
                                .orElseThrow();
                    });

            result.add(specialisation);
        }

        return result;
    }

    private List<Room> seedRooms() {
        List<Room> result = new ArrayList<>();

        for (String[] details : ROOMS) {
            Room room = roomRepository
                    .findByRoomNumber(details[0])
                    .orElseGet(() -> {
                        roomService.create(new RoomRequest(
                                details[0],
                                details[1],
                                details[2],
                                details[0].startsWith("R09")
                                        ? RoomType.PROCEDURE
                                        : RoomType.CONSULTATION,
                                1
                        ));

                        return roomRepository
                                .findByRoomNumber(details[0])
                                .orElseThrow();
                    });

            result.add(room);
        }

        return result;
    }

    private List<Doctor> seedDoctors(
            List<Specialisation> specialisations
    ) {
        // Use the same eligibility rule as patient booking: an available
        // doctor profile is not enough if its account is disabled, deleted,
        // unverified or tied to an inactive specialisation.
        long availableDoctors = doctorRepository.findPublicDirectory().size();

        for (int index = 0; index < DOCTORS.length; index++) {
            String[] details = DOCTORS[index];
            String firstName = details[0];
            String lastName = details[1];
            String email = emailFor(firstName, lastName);
            String registrationNumber = String.format(
                    Locale.ROOT,
                    "SLMC-%05d",
                    10001 + index
            );

            if (!userRepository.existsByEmail(email)) {
                if (availableDoctors >= TARGET_AVAILABLE_DOCTORS) {
                    logger.info(
                            "Eligible doctor roster is full at {}; {} was not created.",
                            availableDoctors,
                            email
                    );
                    continue;
                }

                Specialisation specialisation = specialisations.get(
                        Integer.parseInt(details[3])
                );

                createDemoDoctor(
                        email,
                        demoPassword,
                        firstName,
                        lastName,
                        registrationNumber,
                        details[2],
                        specialisation,
                        FEES[index]
                );

                availableDoctors += 1;
                logger.info("Demo doctor created: {}", email);
            }

            Doctor doctor = doctorRepository
                    .findByRegistrationNumber(registrationNumber)
                    .orElseThrow(() -> new IllegalStateException(
                            "The demo doctor profile could not be created."
                    ));

            if (doctorRepository.findPublicDoctorById(doctor.getId()).isEmpty()) {
                logger.warn(
                    "Demo doctor is not eligible for published sessions: {}",
                    registrationNumber
                );
            }
        }

        // A deleted or suspended account is never restored. If the eligible
        // roster is short, create a fresh replacement doctor so patients keep
        // seeing 40 bookable doctors every day.
        int replacementIndex = 0;
        while (availableDoctors < TARGET_AVAILABLE_DOCTORS) {
            createReplacementDoctor(specialisations, replacementIndex);
            availableDoctors += 1;
            replacementIndex += 1;
        }

        // Include every publicly eligible doctor in the roster, including
        // accounts seeded by earlier releases, so the published week has 40.
        return doctorRepository.findPublicDirectory().stream()
                .sorted(Comparator.comparing(Doctor::getRegistrationNumber))
                .limit(TARGET_AVAILABLE_DOCTORS)
                .toList();
    }

    private void createDemoDoctor(
            String email,
            String password,
            String firstName,
            String lastName,
            String registrationNumber,
            String qualifications,
            Specialisation specialisation,
            BigDecimal fee
    ) {
        doctorService.createDoctor(new CreateDoctorRequest(
                email,
                password,
                password,
                firstName,
                lastName,
                registrationNumber,
                qualifications,
                specialisation.getId(),
                fee
        ));

        User user = userRepository.findByEmail(email).orElseThrow();
        user.activateAfterVerification();
        userRepository.saveAndFlush(user);
    }

    private void createReplacementDoctor(
            List<Specialisation> specialisations,
            int replacementIndex
    ) {
        int candidate = replacementIndex;
        String email;
        String registrationNumber;

        do {
            email = String.format(
                    Locale.ROOT,
                    "relief-doctor-%02d@%s",
                    candidate,
                    EMAIL_DOMAIN
            );
            registrationNumber = String.format(
                    Locale.ROOT,
                    "SLMC-%05d",
                    90000 + candidate
            );
            candidate += 1;
        } while (userRepository.existsByEmail(email)
                || doctorRepository.existsByRegistrationNumber(
                        registrationNumber
                ));

        createDemoDoctor(
                email,
                demoPassword,
                "Relief",
                "Doctor " + candidate,
                registrationNumber,
                "MBBS, MD (General Medicine)",
                specialisations.get(candidate % specialisations.size()),
                FEES[candidate % FEES.length]
        );

        logger.info("Replacement demo doctor created: {}", email);
    }

    private void seedDemoAdmin() {
        String email = "admin@" + EMAIL_DOMAIN;

        if (userRepository.existsByEmail(email)) {
            return;
        }

        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException(
                        "The required ADMIN role is not configured."
                ));

        User admin = new User(
                adminRole,
                email,
                passwordEncoder.encode(demoPassword)
        );
        admin.activateByAdministrator();
        userRepository.saveAndFlush(admin);

        logger.info("Demo administrator created: {}", email);
    }

    private void seedManager() {
        String email = "manager@" + EMAIL_DOMAIN;

        if (userRepository.existsByEmail(email)) {
            return;
        }

        Role managerRole = roleRepository.findByName("MANAGER")
                .orElseThrow(() -> new IllegalStateException(
                        "The required MANAGER role is not configured."
                ));

        User manager = new User(
                managerRole,
                email,
                passwordEncoder.encode(demoPassword)
        );

        manager.activateByAdministrator();

        userRepository.saveAndFlush(manager);

        logger.info("Demo manager created: {}", email);
    }

    /**
     * Retire published sessions outside the six-month roster instead of
     * deleting them. Sessions with a live consultation keep their OPEN status
     * so patient history and the current queue are never broken; other stale
     * sessions are cancelled and replaced by the fresh published roster.
     */
    private void cancelStalePublishedSessions() {
        LocalDate firstDate = LocalDate.now(clock);
        LocalDate lastDate = firstDate.plusDays(PUBLISHED_WEEKS * 7L - 1);
        UUID managerId = userRepository.findByEmail(
                "manager@" + EMAIL_DOMAIN
        ).map(User::getId).orElse(null);

        List<AppointmentStatus> activeStatuses = List.of(
                AppointmentStatus.BOOKED,
                AppointmentStatus.ARRIVED,
                AppointmentStatus.IN_CONSULTATION
        );

        int cancelled = 0;
        for (DoctorSession session : doctorSessionRepository.findAll()) {
            if (session.getStatus() != SessionStatus.OPEN
                    || session.getSessionDate() == null
                    || (!session.getSessionDate().isBefore(firstDate)
                        && !session.getSessionDate().isAfter(lastDate))) {
                continue;
            }

            if (appointmentRepository.countBySession_IdAndStatusIn(
                    session.getId(),
                    activeStatuses
            ) > 0) {
                continue;
            }

            doctorSessionService.cancelSession(
                    session.getId(),
                    managerId,
                    "MANAGER",
                    "Replaced by the published weekly session roster."
            );
            cancelled += 1;
        }

        logger.info("Stale empty sessions cancelled: {}.", cancelled);
    }

    /**
     * Forty available doctors each receive two published sessions on each of
     * three days per week for 26 weeks: 07:00–09:30 and 12:00–14:30, with 30
     * places and five-minute slots each, giving 60 places per doctor/day.
     */
    private void seedSessions(List<Doctor> doctors, List<Room> rooms) {
        LocalDate firstDate = LocalDate.now(clock);

        List<Room> consultationRooms = rooms.stream()
                .filter(room -> room.isActive()
                        && room.getRoomType() != null
                        && "CONSULTATION".equals(room.getRoomType().name()))
                .sorted((left, right) ->
                        left.getRoomNumber().compareTo(right.getRoomNumber()))
                .toList();

        int sessionsCreated = 0;
        for (int week = 0; week < PUBLISHED_WEEKS; week += 1) {
            int doctorIndex = 0;
            for (Doctor doctor : doctors) {
                if (!doctor.isAvailable()) {
                    continue;
                }

                int[] dayOffsets = doctorIndex < DOCTORS_PER_DAY
                        ? MON_WED_FRI
                        : TUE_THU_SAT;
                List<LocalTime[]> windows = List.of(
                        new LocalTime[] {
                                ClinicSchedule.MORNING_START,
                                ClinicSchedule.MORNING_END
                        },
                        new LocalTime[] {
                                ClinicSchedule.AFTERNOON_START,
                                ClinicSchedule.AFTERNOON_END
                        }
                );

                for (int dayOffset : dayOffsets) {
                    LocalDate date = firstDate.plusDays(
                            week * 7L + dayOffset
                    );

                    for (LocalTime[] window : windows) {
                        LocalTime startTime = window[0];
                        LocalTime endTime = window[1];

                        boolean alreadyPublished = doctorSessionRepository
                                .findByDoctorIdAndSessionDateAndStatus(
                                        doctor.getId(),
                                        date,
                                        SessionStatus.OPEN
                                )
                                .stream()
                                .anyMatch(session ->
                                        startTime.equals(session.getStartTime())
                                                && endTime.equals(
                                                        session.getEndTime()
                                                )
                                );
                        if (alreadyPublished) {
                            continue;
                        }

                        Room room = consultationRooms.stream()
                                .filter(candidate -> isRoomAvailable(
                                        candidate,
                                        date,
                                        startTime,
                                        endTime
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
                                        FEES[doctorIndex % FEES.length]
                                )
                        );
                        sessionsCreated += 1;
                    }
                }

                doctorIndex += 1;
            }
        }

        logger.info(
                "Doctor sessions created across {} weeks: {} ({} per doctor per week).",
                PUBLISHED_WEEKS,
                sessionsCreated,
                SESSIONS_PER_DOCTOR_PER_WEEK
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

    private String emailFor(String firstName, String lastName) {
        return (firstName + "." + lastName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.]", "")
                + "@" + EMAIL_DOMAIN;
    }
}
