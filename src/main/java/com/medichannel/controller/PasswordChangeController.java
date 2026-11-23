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
 * Completes an administrator-initiated staff password reset.
 *
 * The endpoint is intentionally token-free: a forced user receives no access
 * token from /login, and existing tokens are rejected while the flag is set.
 * Knowledge of the temporary password proves ownership of the account.
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
