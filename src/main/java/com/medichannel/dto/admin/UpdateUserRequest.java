package com.medichannel.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Editable account details for non-clinical accounts. Doctor professional
 * details are updated through the doctor endpoint.
 */
public record UpdateUserRequest(

        @NotBlank(message = "Email is required.")
        @Email(message = "Please provide a valid email address.")
        @Size(max = 254)
        String email,

        @Size(max = 100)
        String firstName,

        @Size(max = 100)
        String lastName
) {
}
