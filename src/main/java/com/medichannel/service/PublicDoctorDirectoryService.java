package com.medichannel.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * The hospital's default clinic day is 07:00-16:00 (eight hours). A published
 * session can override those times for a specific date; when no session is
 * live, the default hours are still displayed so visitors can plan a visit.
 */
@Service
public class PublicDoctorDirectoryService {

    public static final LocalTime DEFAULT_START_TIME = LocalTime.of(7, 0);
    public static final LocalTime DEFAULT_END_TIME = LocalTime.of(16, 0);
    public static final int DEFAULT_HOURS = 8;

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

        return doctorRepository
                .findPublicDirectoryIncludingUnavailable()
                .stream()
                .map((doctor) -> toEntry(doctor, today))
                .toList();
    }

    private DoctorDirectoryEntry toEntry(Doctor doctor, LocalDate today) {
        List<DoctorSession> todaySessions = doctorSessionRepository
                .findByDoctor_IdAndSessionDate(doctor.getId(), today);

        DoctorSession activeSession = todaySessions.stream()
                .filter((session) ->
                        ACTIVE_PRIORITY.containsKey(session.getStatus())
                )
                .min(ACTIVE_SESSION_ORDER)
                .orElse(null);

        DoctorSession sessionForDetails = activeSession;
        if (sessionForDetails == null) {
            List<DoctorSession> candidates = new ArrayList<>(todaySessions);
            candidates.addAll(
                    doctorSessionRepository
                            .findByDoctor_IdAndSessionDateGreaterThanEqualOrderBySessionDateAscStartTimeAsc(
                                    doctor.getId(),
                                    today.plusDays(1)
                            )
            );

            sessionForDetails = candidates.stream()
                    .filter((session) ->
                            ACTIVE_PRIORITY.containsKey(session.getStatus())
                    )
                    .min(UPCOMING_SESSION_ORDER)
                    .orElse(null);
        }

        SessionQueueSummary queue = activeSession == null
                ? null
                : sessionQueueService.buildSummary(activeSession);

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
                DEFAULT_START_TIME,
                DEFAULT_END_TIME,
                DEFAULT_HOURS,
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
