package com.medichannel.repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.medichannel.entity.DoctorSession;
import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.SessionStatus;

public interface DoctorSessionRepository
        extends JpaRepository<DoctorSession, UUID> {

    List<DoctorSession> findByDoctorIdAndSessionDateAndStatus(
            UUID doctorId,
            LocalDate date,
            SessionStatus status
    );

    List<DoctorSession> findByDoctor_IdAndSessionDate(
            UUID doctorId,
            LocalDate sessionDate
    );

    List<DoctorSession> findByDoctor_Id(UUID doctorId);

    List<DoctorSession> findByRoom_IdAndSessionDate(UUID roomId, LocalDate date);

    List<DoctorSession>
            findByDoctor_IdAndSessionDateGreaterThanEqualOrderBySessionDateAscStartTimeAsc(
                    UUID doctorId,
                    LocalDate from
            );

    List<DoctorSession> findBySessionDateAndStatus(
            LocalDate date,
            SessionStatus status
    );

    List<DoctorSession> findByDoctorIdAndStatus(
            UUID doctorId,
            SessionStatus status
    );

    // Doctor portal: all sessions belonging to the authenticated doctor.
    List<DoctorSession>
            findByDoctor_User_IdOrderBySessionDateDescStartTimeDesc(
                    UUID userId
            );

    List<DoctorSession>
            findByDoctor_User_IdAndSessionDateOrderByStartTimeAsc(
                    UUID userId,
                    LocalDate sessionDate
            );

    Optional<DoctorSession> findByIdAndDoctor_User_Id(
            UUID sessionId,
            UUID userId
    );

    // Publicly discoverable sessions must be open, in the future, inside the
    // published clinic day with a 5–30 minute slot per patient, have remaining
    // capacity, and belong to an available, eligible doctor.
    @Query("""
            SELECT s
            FROM DoctorSession s
            JOIN FETCH s.doctor d
            JOIN FETCH d.specialisation sp
            JOIN FETCH d.user u
            LEFT JOIN FETCH s.room r
            WHERE s.status = :status
              AND (
                    :sessionDate IS NOT NULL
                    OR s.sessionDate >= :today
              )
              AND (
                    :sessionDate IS NULL
                    OR CAST(s.sessionDate AS string) = :sessionDate
              )
              AND (
                    :fromDate IS NULL
                    OR CAST(s.sessionDate AS string) >= :fromDate
              )
              AND (
                    :toDate IS NULL
                    OR CAST(s.sessionDate AS string) <= :toDate
              )
              AND (
                    s.sessionDate > :today
                    OR (
                        s.sessionDate = :today
                        AND (
                            (
                                s.consultationType = :physicalType
                                AND s.startTime > :physicalBookingCutoff
                            )
                            OR (
                                (s.consultationType IS NULL
                                    OR s.consultationType <> :physicalType)
                                AND s.startTime > :onlineBookingCutoff
                            )
                        )
                    )
              )
              AND (SELECT COUNT(a) FROM Appointment a
                   WHERE a.session.id = s.id
                     AND a.status IN :activeStatuses)
                  < s.maxAppointments
              AND (
                    (s.startTime = :morningStart AND s.endTime = :morningEnd)
                    OR (s.startTime = :afternoonStart AND s.endTime = :afternoonEnd)
              )
              AND s.minutesPerPatient = :minutesPerPatient
              AND s.maxAppointments <= :maxAppointmentsPerSession
              AND d.available = true
              AND sp.active = true
              AND u.accountStatus = 'ACTIVE'
              AND u.emailVerified = true
              AND (:doctorId IS NULL OR d.id = :doctorId)
              AND (:specialisationId IS NULL OR sp.id = :specialisationId)
              AND (
                    :q IS NULL
                    OR LOWER(CONCAT(d.firstName, ' ', d.lastName))
                        LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
              )
              AND (
                    :consultationType IS NULL
                    OR s.consultationType = :consultationType
              )
            ORDER BY s.sessionDate ASC, s.startTime ASC
            """)
    List<DoctorSession> findPublicOpenSessions(
            @Param("status") SessionStatus status,
            @Param("today") LocalDate today,
            @Param("physicalType") ConsultationType physicalType,
            @Param("physicalBookingCutoff") LocalTime physicalBookingCutoff,
            @Param("onlineBookingCutoff") LocalTime onlineBookingCutoff,
            @Param("doctorId") UUID doctorId,
            @Param("specialisationId") UUID specialisationId,
            @Param("sessionDate") String sessionDate,
            @Param("fromDate") String fromDate,
            @Param("toDate") String toDate,
            @Param("q") String q,
            @Param("consultationType") ConsultationType consultationType,
            @Param("morningStart") LocalTime morningStart,
            @Param("morningEnd") LocalTime morningEnd,
            @Param("afternoonStart") LocalTime afternoonStart,
            @Param("afternoonEnd") LocalTime afternoonEnd,
            @Param("minutesPerPatient") int minutesPerPatient,
            @Param("maxAppointmentsPerSession") int maxAppointmentsPerSession,
            @Param("activeStatuses") Collection<AppointmentStatus> activeStatuses
    );

    /**
     * Daily 60-patient rule: count every non-cancelled place a doctor has
     * published on the requested date. The session being edited is excluded
     * by the caller so its old value is not counted twice.
     */
    @Query("""
            SELECT COALESCE(SUM(s.maxAppointments), 0)
            FROM DoctorSession s
            WHERE s.doctor.id = :doctorId
              AND s.sessionDate = :sessionDate
              AND s.status <> :cancelledStatus
              AND (:excludedSessionId IS NULL OR s.id <> :excludedSessionId)
            """)
    long sumActiveMaxAppointmentsByDoctorAndDate(
            @Param("doctorId") UUID doctorId,
            @Param("sessionDate") LocalDate sessionDate,
            @Param("cancelledStatus") SessionStatus cancelledStatus,
            @Param("excludedSessionId") UUID excludedSessionId
    );
}
