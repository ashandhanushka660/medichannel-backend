package com.medichannel.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.medichannel.dto.queue.SessionQueueSummary;
import com.medichannel.entity.Appointment;
import com.medichannel.entity.DoctorSession;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.exception.ResourceNotFoundException;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorSessionRepository;

/**
 * Builds the live queue state for a session and broadcasts it to
 * connected clients after the surrounding transaction commits.
 *
 * The queue pointer is derived from appointment numbers allocated by
 * the database. Numbers are never reassigned.
 */
@Service
public class SessionQueueService {

    public static final String SESSION_TOPIC_PREFIX = "/topic/sessions/";
    public static final String SESSION_TOPIC_SUFFIX = "/queue";
    public static final String CATALOGUE_TOPIC = "/topic/sessions";

    private static final List<AppointmentStatus> ACTIVE_STATUSES =
            List.of(
                    AppointmentStatus.BOOKED,
                    AppointmentStatus.ARRIVED,
                    AppointmentStatus.IN_CONSULTATION
            );

    private final AppointmentRepository appointmentRepository;
    private final DoctorSessionRepository doctorSessionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public SessionQueueService(
            AppointmentRepository appointmentRepository,
            DoctorSessionRepository doctorSessionRepository,
            SimpMessagingTemplate messagingTemplate,
            Clock clock
    ) {
        this.appointmentRepository = appointmentRepository;
        this.doctorSessionRepository = doctorSessionRepository;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SessionQueueSummary getSummary(UUID sessionId) {
        DoctorSession session = doctorSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Doctor session was not found."
                ));

        return buildSummary(session);
    }

    /**
     * Compute the queue for an already loaded session. Must be called
     * from inside the transaction that changed the appointment state.
     */
    public SessionQueueSummary buildSummary(DoctorSession session) {
        UUID sessionId = session.getId();

        Integer nowServing = appointmentRepository
                .findFirstBySession_IdAndStatusOrderByAppointmentNumberAsc(
                        sessionId,
                        AppointmentStatus.IN_CONSULTATION
                )
                .map(Appointment::getAppointmentNumber)
                .orElse(null);

        /*
         * BR-03: the database counter is the only source of the next
         * allocatable number. Cancelled and no-show numbers are never
         * handed out again.
         */
        Integer storedLastNumber =
                appointmentRepository.findLastAppointmentNumberBySessionId(
                        sessionId
                );
        int lastNumber = storedLastNumber == null ? 0 : storedLastNumber;

        /*
         * RT-02: the queue length counts only patients who still occupy
         * a live place. Cancelled and no-show appointments remain in
         * history but no longer occupy capacity.
         */
        int remaining = Math.max(
                0,
                session.getMaxAppointments()
                        - (int) appointmentRepository
                                .countBySession_IdAndStatusIn(
                                        sessionId,
                                        ACTIVE_STATUSES
                                )
        );

        return summary(
                session,
                nowServing,
                lastNumber,
                remaining,
                clock.instant()
        );
    }

    /**
     * Batched queue summaries for read-only pages that render many live
     * sessions at once (the public doctor directory). The query count is
     * fixed, so adding doctors cannot multiply database round trips.
     */
    @Transactional(readOnly = true)
    public Map<UUID, SessionQueueSummary> buildSummaries(
            Collection<DoctorSession> sessions
    ) {
        if (sessions == null || sessions.isEmpty()) {
            return Map.of();
        }

        List<UUID> sessionIds = sessions.stream()
                .map(DoctorSession::getId)
                .distinct()
                .toList();

        Map<UUID, Integer> nowServing = appointmentRepository
                .findFirstNumbersBySessionIds(
                        sessionIds,
                        AppointmentStatus.IN_CONSULTATION
                )
                .stream()
                .collect(Collectors.toMap(
                        AppointmentRepository.SessionFirstNumber::getSessionId,
                        AppointmentRepository.SessionFirstNumber::getFirstNumber
                ));

        Map<UUID, Long> activeCounts = appointmentRepository
                .countActiveBySessionIds(sessionIds, ACTIVE_STATUSES)
                .stream()
                .collect(Collectors.toMap(
                        AppointmentRepository.SessionActiveCount::getSessionId,
                        AppointmentRepository.SessionActiveCount::getActiveCount
                ));

        Map<UUID, Integer> lastNumbers = new HashMap<>();
        for (Object[] row : appointmentRepository
                .findLastAppointmentNumbersBySessionIds(sessionIds)) {
            lastNumbers.put(
                    (UUID) row[0],
                    ((Number) row[1]).intValue()
            );
        }

        Instant computedAt = clock.instant();
        Map<UUID, SessionQueueSummary> summaries = new HashMap<>();

        for (DoctorSession session : sessions) {
            long activeCount = activeCounts.getOrDefault(
                    session.getId(),
                    0L
            );

            summaries.put(
                    session.getId(),
                    summary(
                            session,
                            nowServing.get(session.getId()),
                            lastNumbers.getOrDefault(session.getId(), 0),
                            Math.max(
                                    0,
                                    session.getMaxAppointments()
                                            - (int) activeCount
                            ),
                            computedAt
                    )
            );
        }

        return summaries;
    }

    private SessionQueueSummary summary(
            DoctorSession session,
            Integer nowServing,
            int lastNumber,
            int remaining,
            Instant computedAt
    ) {
        int availableSlots = Math.max(
                0,
                session.getMaxAppointments() - lastNumber
        );
        Integer nextNumber = availableSlots == 0 ? null : lastNumber + 1;

        return new SessionQueueSummary(
                session.getId(),
                nowServing,
                nextNumber,
                remaining,
                availableSlots,
                session.getMaxAppointments(),
                session.getStatus(),
                computedAt
        );
    }

    /**
     * Publish the queue to everyone watching this session, plus the
     * catalogue topic so search results can refresh capacity.
     */
    public void broadcast(SessionQueueSummary summary) {
        Runnable publish = () -> {
            messagingTemplate.convertAndSend(
                    SESSION_TOPIC_PREFIX + summary.sessionId()
                            + SESSION_TOPIC_SUFFIX,
                    summary
            );
            messagingTemplate.convertAndSend(CATALOGUE_TOPIC, summary);
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    }
            );
            return;
        }

        publish.run();
    }

    public void broadcastAppointmentStatus(
            UUID appointmentId,
            AppointmentStatus status,
            Instant changedAt
    ) {
        Runnable publish = () -> messagingTemplate.convertAndSend(
                "/topic/appointments/" + appointmentId + "/status",
                new AppointmentStatusEvent(appointmentId, status, changedAt)
        );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    }
            );
            return;
        }

        publish.run();
    }

    public record AppointmentStatusEvent(
            UUID appointmentId,
            AppointmentStatus status,
            Instant changedAt
    ) {
    }
}
