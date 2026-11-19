package com.medichannel.dto.appointment;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A reception booking for a patient who is at the hospital and may not have
 * an online account. Reception supplies the minimum contact details needed
 * to create a patient record and issue an appointment number.
 */
public record WalkInBookingRequest(

        @NotNull(message = "Consultation session is required.")
        UUID sessionId,

        @NotBlank(message = "Patient first name is required.")
        @Size(max = 100, message = "Patient first name must not exceed 100 characters.")
        String firstName,

        @NotBlank(message = "Patient last name is required.")
        @Size(max = 100, message = "Patient last name must not exceed 100 characters.")
        String lastName,

        @NotBlank(message = "Patient mobile number is required.")
        @Pattern(
                regexp = "^07[0-9]{8}$",
                message = "Please provide a valid mobile number in the 07XXXXXXXX format."
        )
        String phone,

        @NotBlank(message = "Consultation reason is required.")
        @Size(max = 2000, message = "Consultation reason must not exceed 2000 characters.")
        String reason

) {
}
