package com.medichannel.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.medichannel.enums.AppointmentStatus;
import com.medichannel.enums.ConsultationType;
import com.medichannel.enums.PaymentMethod;
import com.medichannel.enums.PaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "appointments",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_appointment_session_number",
            columnNames = {"session_id", "appointment_number"}
        ),
        @UniqueConstraint(
            name = "uq_appointment_booking_reference",
            columnNames = {"booking_reference"}
        )
    }
)
public class Appointment {

    /**
     * Staff/system cancellations are legitimate even for paid or
     * in-consultation appointments (for example when a session is
     * cancelled). The database trigger recognises these markers; every
     * other cancellation must satisfy the patient booking rule.
     */
    public static final String STAFF_CANCELLATION_PREFIX = "STAFF:";
    public static final String SYSTEM_CANCELLATION_PREFIX = "SYSTEM:";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "session_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_appointments_session")
    )
    private DoctorSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "patient_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_appointments_patient")
    )
    private Patient patient;

    /*
     * PostgreSQL assigns this value through the
     * trg_assign_appointment_number BEFORE INSERT trigger.
     *
     * Hibernate retrieves the generated value after insertion.
     * Never assign this field from application code.
     */
    @Generated(event = EventType.INSERT)
    @Column(
        name = "appointment_number",
        nullable = false,
        updatable = false
    )
    private Integer appointmentNumber;

    @Column(
        name = "booking_reference",
        nullable = false,
        unique = true,
        updatable = false
    )
    private UUID bookingReference;

    @Column(
        name = "reason_encrypted",
        nullable = false,
        columnDefinition = "TEXT"
    )
    private String reasonEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AppointmentStatus status = AppointmentStatus.BOOKED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "acceptance_status", nullable = false, length = 20)
    private String acceptanceStatus = "PENDING";

    @Column(name = "doctor_accepted_at")
    private Instant doctorAcceptedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "delay_minutes", nullable = false)
    private int delayMinutes;

    @Column(name = "cancellation_reason_encrypted", columnDefinition = "TEXT")
    private String cancellationReasonEncrypted;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "consultation_started_at")
    private Instant consultationStartedAt;

    @Column(name = "consultation_completed_at")
    private Instant consultationCompletedAt;

    /*
     * Participant-scoped history visibility. A timestamp means that participant
     * has cleared the row from their own view. The appointment record itself is
     * never deleted: payments, consultation summaries, chat and audits still
     * reference it.
     */
    @Column(name = "patient_hidden_at")
    private Instant patientHiddenAt;

    @Column(name = "doctor_hidden_at")
    private Instant doctorHiddenAt;

    protected Appointment() {
    }

    public Appointment(
        DoctorSession session,
        Patient patient,
        String reasonEncrypted
    ) {
        if (session == null) {
            throw new IllegalArgumentException(
                "Appointment session is required."
            );
        }

        if (patient == null) {
            throw new IllegalArgumentException(
                "Appointment patient is required."
            );
        }

        if (reasonEncrypted == null || reasonEncrypted.isBlank()) {
            throw new IllegalArgumentException(
                "Appointment reason is required."
            );
        }

        this.session = session;
        this.patient = patient;
        this.reasonEncrypted = reasonEncrypted;
        this.bookingReference = UUID.randomUUID();
        this.status = AppointmentStatus.BOOKED;
        this.paymentStatus = PaymentStatus.PENDING;
    }

    public void cancel(Instant cancellationTime) {
        if (status != AppointmentStatus.BOOKED) {
            throw new IllegalStateException(
                "Only booked appointments can be cancelled."
            );
        }

        if (paymentStatus != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                "Paid appointments cannot be cancelled through this operation."
            );
        }

        if (cancellationTime == null) {
            throw new IllegalArgumentException(
                "Cancellation time is required."
            );
        }

        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = cancellationTime;
        this.cancellationReasonEncrypted =
                STAFF_CANCELLATION_PREFIX + "CANCELLED";
    }

    /**
     * Patient-initiated cancellation. Only an unpaid booking may be
     * cancelled by the patient; the service additionally enforces the
     * three-hour cut-off using the application clock.
     */
    public void cancelForPatient(
        Instant cancellationTime,
        String encryptedCancellationReason
    ) {
        if (status == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException("Appointment is already cancelled.");
        }
        if (status != AppointmentStatus.BOOKED) {
            throw new IllegalStateException(
                    "Only booked appointments can be cancelled."
            );
        }
        if (paymentStatus != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Paid appointments cannot be cancelled by patients."
            );
        }
        if (cancellationTime == null) {
            throw new IllegalArgumentException("Cancellation time is required.");
        }
        if (encryptedCancellationReason == null
                || encryptedCancellationReason.isBlank()) {
            throw new IllegalArgumentException(
                    "Cancellation reason is required."
            );
        }

        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = cancellationTime;
        this.cancellationReasonEncrypted = encryptedCancellationReason;
    }

    public void cancelByStaff(Instant cancellationTime) {
        cancelByStaff(cancellationTime, null);
    }

    public void cancelByStaff(
        Instant cancellationTime,
        String encryptedCancellationReason
    ) {
        if (status == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException("Appointment is already cancelled.");
        }
        if (status == AppointmentStatus.COMPLETED) {
            throw new IllegalStateException("Completed appointments cannot be cancelled.");
        }
        if (cancellationTime == null) {
            throw new IllegalArgumentException("Cancellation time is required.");
        }
        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = cancellationTime;
        this.cancellationReasonEncrypted = encryptedCancellationReason == null
                || encryptedCancellationReason.isBlank()
                ? SYSTEM_CANCELLATION_PREFIX + "Cancelled by hospital staff"
                : STAFF_CANCELLATION_PREFIX + encryptedCancellationReason;
    }

    public void acceptByDoctor(Instant time) {
        if (status == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException("Cancelled appointments cannot be accepted.");
        }
        this.acceptanceStatus = "ACCEPTED";
        this.doctorAcceptedAt = time;
    }

    public void declineByDoctor(
        Instant time,
        String encryptedCancellationReason
    ) {
        if (status == AppointmentStatus.COMPLETED) {
            throw new IllegalStateException("Completed appointments cannot be declined.");
        }
        if (time == null) {
            throw new IllegalArgumentException("Cancellation time is required.");
        }
        if (encryptedCancellationReason == null
                || encryptedCancellationReason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required.");
        }
        this.acceptanceStatus = "DECLINED";
        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = time;
        this.cancellationReasonEncrypted =
                STAFF_CANCELLATION_PREFIX + encryptedCancellationReason;
    }

    public void addDelay(int minutes) {
        if (minutes < 0) {
            throw new IllegalArgumentException("Delay cannot be negative.");
        }
        this.delayMinutes += minutes;
    }

    public int getDelayMinutes() {
        return delayMinutes;
    }

    public String getAcceptanceStatus() {
        return acceptanceStatus;
    }

    public Instant getDoctorAcceptedAt() {
        return doctorAcceptedAt;
    }

    public void markPaid(PaymentMethod method) {
        if (status == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException(
                "Cancelled appointments cannot be paid."
            );
        }

        if (paymentStatus == PaymentStatus.PAID) {
            throw new IllegalStateException(
                "Appointment has already been paid."
            );
        }

        if (method == null) {
            throw new IllegalArgumentException(
                "Payment method is required."
            );
        }

        this.paymentMethod = method;
        this.paymentStatus = PaymentStatus.PAID;
    }

    public void markArrived(Instant arrivalTime) {
        if (status != AppointmentStatus.BOOKED) {
            throw new IllegalStateException(
                "Only booked appointments can be marked as arrived."
            );
        }

        if (arrivalTime == null) {
            throw new IllegalArgumentException(
                "Arrival time is required."
            );
        }

        this.arrivedAt = arrivalTime;
        status = AppointmentStatus.ARRIVED;
    }

    public void startConsultation(Instant startTime) {
        if (status != AppointmentStatus.BOOKED
                && status != AppointmentStatus.ARRIVED) {
            throw new IllegalStateException(
                "Appointment cannot enter consultation from its current status."
            );
        }

        if (session != null
                && session.getConsultationType() == ConsultationType.PHYSICAL
                && status != AppointmentStatus.ARRIVED) {
            throw new IllegalStateException(
                "Physical consultations require patient check-in before they can start."
            );
        }

        if (startTime == null) {
            throw new IllegalArgumentException(
                "Consultation start time is required."
            );
        }

        if (status == AppointmentStatus.BOOKED) {
            // Online consultations may start without a separate check-in step.
            this.arrivedAt = startTime;
        }

        this.consultationStartedAt = startTime;
        status = AppointmentStatus.IN_CONSULTATION;
    }

    public void complete(Instant completionTime) {
        if (status != AppointmentStatus.IN_CONSULTATION) {
            throw new IllegalStateException(
                "Only appointments in consultation can be completed."
            );
        }

        if (completionTime == null) {
            throw new IllegalArgumentException(
                "Consultation completion time is required."
            );
        }

        if (consultationStartedAt == null
                || completionTime.isBefore(consultationStartedAt)) {
            throw new IllegalArgumentException(
                "Completion time cannot be before the consultation start."
            );
        }

        this.consultationCompletedAt = completionTime;
        status = AppointmentStatus.COMPLETED;
    }

    public void markNoShow() {
        if (status != AppointmentStatus.BOOKED
                && status != AppointmentStatus.ARRIVED) {
            throw new IllegalStateException(
                "This appointment cannot be marked as a no-show."
            );
        }

        status = AppointmentStatus.NO_SHOW;
    }

    /**
     * Clear this row from the patient's own history view. Only closed rows can
     * be hidden; bookings that still affect the queue or consultation access
     * must remain visible.
     */
    public void hideFromPatient(Instant hiddenAt) {
        ensureHistoryCanBeHidden();
        if (hiddenAt == null) {
            throw new IllegalArgumentException("Hidden time is required.");
        }
        if (patientHiddenAt == null) {
            patientHiddenAt = hiddenAt;
        }
    }

    /** Clear this row from the assigned doctor's own history view. */
    public void hideFromDoctor(Instant hiddenAt) {
        ensureHistoryCanBeHidden();
        if (hiddenAt == null) {
            throw new IllegalArgumentException("Hidden time is required.");
        }
        if (doctorHiddenAt == null) {
            doctorHiddenAt = hiddenAt;
        }
    }

    private void ensureHistoryCanBeHidden() {
        if (status != AppointmentStatus.COMPLETED
                && status != AppointmentStatus.NO_SHOW
                && status != AppointmentStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Only completed or closed appointments can be cleared from history."
            );
        }
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;

        if (bookingReference == null) {
            bookingReference = UUID.randomUUID();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public DoctorSession getSession() {
        return session;
    }

    public Patient getPatient() {
        return patient;
    }

    public Integer getAppointmentNumber() {
        return appointmentNumber;
    }

    public UUID getBookingReference() {
        return bookingReference;
    }

    public String getReasonEncrypted() {
        return reasonEncrypted;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancellationReasonEncrypted() {
        return cancellationReasonEncrypted;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public Instant getConsultationStartedAt() {
        return consultationStartedAt;
    }

    public Instant getConsultationCompletedAt() {
        return consultationCompletedAt;
    }
}
