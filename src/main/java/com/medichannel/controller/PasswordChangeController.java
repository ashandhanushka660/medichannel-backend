package com.medichannel.controller;

import com.medichannel.service.AuthService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Optional self-service password change for an account owner.
 *
 * The endpoint is intentionally token-free: knowledge of the current password
 * proves ownership. Administrator password resets do not force users through
 * this flow; staff can sign in immediately with the reset password.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class PasswordChangeController {

    private final AuthService authService;

    public PasswordChangeController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        authService.changePassword(
                request.email(),
                request.currentPassword(),
                request.newPassword(),
                request.confirmPassword()
        );

        return ResponseEntity.noContent().build();
    }

    public record ChangePasswordRequest(

            @NotBlank(message = "Email is required.")
            @Email(message = "Please provide a valid email address.")
            String email,

            @NotBlank(message = "The temporary password is required.")
            String currentPassword,

            @NotBlank(message = "A new password is required.")
            String newPassword,

            @NotBlank(message = "Password confirmation is required.")
            String confirmPassword
    ) {
    }
}
