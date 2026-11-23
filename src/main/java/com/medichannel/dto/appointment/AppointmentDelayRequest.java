package com.medichannel.dto.appointment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AppointmentDelayRequest(

        @NotNull(message = "Delay is required.")
        @Min(value = 0, message = "Delay cannot be negative.")
        @Max(value = 480, message = "Delay cannot exceed eight hours.")
        Integer delayMinutes

) {
}
