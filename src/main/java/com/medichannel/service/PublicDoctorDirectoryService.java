package com.medichannel.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.config.ClinicSchedule;
import com.medichannel.dto.directory.DoctorDirectoryEntry;
import com.medichannel.dto.queue.SessionQueueSummary;
import com.medichannel.entity.Doctor;
import com.medichannel.entity.DoctorSession;
import com.medichannel.entity.Room;
import com.medichannel.enums.SessionStatus;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.DoctorSessionRepository;

/**
 * Public roster shown on the guest dashboard before a patient signs in.
 *
 * Every doctor is advertised with the hospital-day envelope of 07:00-16:00.
 * Only sessions in the published 07:00–11:00 or 13:00–15:00 windows with
 * five-minute slots are used for room, consultation mode and live queue
 * details, so the guest dashboard and patient booking dashboard agree.
 */
@Service
public class PublicDoctorDirectoryService {

    private static final Map<SessionStatus, Integer> ACTIVE_PRIORITY =
            Map.of(
                    SessionStatus.IN_PROGRESS, 0,
                    SessionStatus.OPEN, 1,
                    SessionStatus.SCHEDULED, 2
            );

    private static final Comparator<DoctorSession> ACTIVE_SESSION_ORDER =
            Comparator
                    .comparingInt((DoctorSession session) ->
                            ACTIVE_PRIORITY.getOrDefault(
                                    session.getStatus(),
                                    99
                            ))
                    .thenComparing(DoctorSession::getStartTime);

    private static final Comparator<DoctorSession> UPCOMING_SESSION_ORDER =
            Comparator
                    .comparing(DoctorSession::getSessionDate)
                    .thenComparing(DoctorSession::getStartTime);

    private final DoctorRepository doctorRepository;
    private final DoctorSessionRepository doctorSessionRepository;
    private final SessionQueueService sessionQueueService;
    private final Clock clock;

    public PublicDoctorDirectoryService(
            DoctorRepository doctorRepository,
            DoctorSessionRepository doctorSessionRepository,
            SessionQueueService sessionQueueService,
            Clock clock
    ) {
        this.doctorRepository = doctorRepository;
        this.doctorSessionRepository = doctorSessionRepository;
        this.sessionQueueService = sessionQueueService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DoctorDirectoryEntry> getDirectory() {
        LocalDate today = LocalDate.now(clock);

        List<Doctor> doctors =
                doctorRepository.findPublicDirectoryIncludingUnavailable();

        if (doctors.isEmpty()) {
            return List.of();
        }

        /*
         * One query loads every session for the whole roster instead of
         * one (or two) per doctor. The directory is rendered for every
         * guest visit, so a per-doctor query pattern would multiply by
         * the size of the roster.
         */
        Map<UUID, List<DoctorSession>> sessionsByDoctor =
                doctorSessionRepository
                        .findDirectorySessions(
                                doctors.stream()
                                        .map(Doctor::getId)
                                        .toList(),
                                today
                        )
                        .stream()
                        .collect(Collectors.groupingBy(
                                session -> session.getDoctor().getId()
                        ));

        Map<UUID, DoctorSession> activeByDoctor = new HashMap<>();
        Map<UUID, DoctorSession> detailsByDoctor = new HashMap<>();

        for (Doctor doctor : doctors) {
            List<DoctorSession> doctorSessions =
                    sessionsByDoctor.getOrDefault(
                            doctor.getId(),
                            List.of()
                    );

            DoctorSession activeSession = doctorSessions.stream()
                    .filter(session -> session.getSessionDate().equals(today))
                    .filter((session) ->
                            ACTIVE_PRIORITY.containsKey(session.getStatus())
                                    && ClinicSchedule.isBookableSession(session)
                    )
                    .min(ACTIVE_SESSION_ORDER)
                    .orElse(null);

            DoctorSession sessionForDetails = activeSession != null
                    ? activeSession
                    : doctorSessions.stream()
                            .filter((session) ->
                                    ACTIVE_PRIORITY.containsKey(
                                            session.getStatus()
                                    )
                                            && ClinicSchedule
                                                    .isBookableSession(session)
                            )
                            .min(UPCOMING_SESSION_ORDER)
                            .orElse(null);

            if (activeSession != null) {
                activeByDoctor.put(doctor.getId(), activeSession);
            }

            if (sessionForDetails != null) {
                detailsByDoctor.put(doctor.getId(), sessionForDetails);
            }
        }

        // Fixed number of queue queries for the whole page.
        Map<UUID, SessionQueueSummary> queues =
                sessionQueueService.buildSummaries(
                        activeByDoctor.values()
                );

        return doctors.stream()
                .map((doctor) -> {
                    DoctorSession activeSession =
                            activeByDoctor.get(doctor.getId());

                    return toEntry(
                            doctor,
                            detailsByDoctor.get(doctor.getId()),
                            activeSession,
                            activeSession == null
                                    ? null
                                    : queues.get(activeSession.getId())
                    );
                })
                .toList();
    }

    private DoctorDirectoryEntry toEntry(
            Doctor doctor,
            DoctorSession sessionForDetails,
            DoctorSession activeSession,
            SessionQueueSummary queue
    ) {
        String name = ("Dr. " + doctor.getFirstName() + " "
                + doctor.getLastName()).replaceAll("\\s+", " ").trim();
        Room room = sessionForDetails == null
                ? null
                : sessionForDetails.getRoom();

        return new DoctorDirectoryEntry(
                doctor.getId(),
                name,
                doctor.getFirstName(),
                doctor.getLastName(),
                doctor.getSpecialisation().getId(),
                doctor.getSpecialisation().getName(),
                doctor.getConsultationFee(),
                doctor.isAvailable(),
                room == null ? null : room.getRoomNumber(),
                room == null ? null : room.getName(),
                ClinicSchedule.START_TIME,
                ClinicSchedule.END_TIME,
                ClinicSchedule.HOURS,
                ClinicSchedule.MINUTES_PER_PATIENT,
                activeSession == null ? null : activeSession.getId(),
                activeSession == null ? null : activeSession.getSessionDate(),
                activeSession == null ? null : activeSession.getStartTime(),
                activeSession == null ? null : activeSession.getEndTime(),
                activeSession == null ? null : activeSession.getConsultationType(),
                activeSession == null ? null : activeSession.getStatus(),
                queue == null ? null : queue.nowServing(),
                queue == null ? null : queue.nextNumber(),
                queue == null ? 0 : queue.remainingAppointments(),
                activeSession == null ? 0 : activeSession.getMaxAppointments()
        );
    }
}
