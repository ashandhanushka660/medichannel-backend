package com.medichannel.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.auth.LoginRequest;
import com.medichannel.dto.auth.LoginResponse;
import com.medichannel.dto.auth.RegisterRequest;
import com.medichannel.dto.auth.RegisterResponse;
import com.medichannel.entity.Role;
import com.medichannel.entity.User;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.InvalidCredentialsException;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.security.JwtService;
import com.medichannel.security.PasswordPolicy;
import com.medichannel.service.AuditLogService;

@Service
public class AuthService {

    /** Published when a user's tokens must stop being accepted. */
    public record WebSocketSessionRevokedEvent(UUID userId) {
    }

    private static final String CUSTOMER_ROLE = "CUSTOMER";
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttemptService;
    private final boolean autoActivateRegistrations;

    private final String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginAttemptService loginAttemptService,
            @Value("${app.registration.auto-activate:true}")
            boolean autoActivateRegistrations
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.autoActivateRegistrations = autoActivateRegistrations;

        // Used to reduce timing differences when an email does not exist.
        this.dummyPasswordHash = passwordEncoder.encode(
                "medichannel-invalid-login-timing-value"
        );
    }

    @Transactional
    public RegisterResponse registerCustomer(RegisterRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Registration information is required."
            );
        }

        String normalizedEmail = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (!request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException(
                    "Passwords do not match."
            );
        }

        int passwordBytes = request.password()
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (passwordBytes > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new IllegalArgumentException(
                    "Password must not exceed 72 bytes when encoded as UTF-8."
            );
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException(
                    "An account with this email already exists."
            );
        }

        com.medichannel.security.PasswordPolicy.validate(request.password());

        Role customerRole = roleRepository.findByName(CUSTOMER_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "The required CUSTOMER role is not configured."
                ));

        String passwordHash = passwordEncoder.encode(request.password());

        User user = new User(
                customerRole,
                normalizedEmail,
                passwordHash
        );

        User savedUser = userRepository.saveAndFlush(user);

        if (autoActivateRegistrations) {
            savedUser.activateAfterVerification();
            savedUser = userRepository.saveAndFlush(savedUser);
        }

        String message = autoActivateRegistrations
                ? "Registration successful. You can now sign in."
                : "Registration received. Your account is pending activation.";

        return new RegisterResponse(
                message,
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getAccountStatus()
        );
    }

    public LoginResponse login(LoginRequest request) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Login information is required."
            );
        }

        String normalizedEmail = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        String submittedPassword = request.password();

        int passwordBytes = submittedPassword
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (passwordBytes > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new InvalidCredentialsException();
        }

        Optional<User> optionalUser =
                loginAttemptService.findUserForLogin(normalizedEmail);

        if (optionalUser.isEmpty()) {
            passwordEncoder.matches(
                    submittedPassword,
                    dummyPasswordHash
            );

            auditLogin(
                    null,
                    normalizedEmail,
                    "LOGIN_FAILED",
                    "Invalid credentials"
            );

            throw new InvalidCredentialsException();
        }

        User user = optionalUser.get();

        boolean passwordMatches = passwordEncoder.matches(
                submittedPassword,
                user.getPasswordHash()
        );

        if (!passwordMatches) {
            loginAttemptService.recordFailedAttempt(normalizedEmail);
            auditLogin(
                    user.getId(),
                    null,
                    "LOGIN_FAILED",
                    "Invalid credentials"
            );
            throw new InvalidCredentialsException();
        }

        // A reset staff password is a temporary credential. Do not issue a
        // normal access token until the owner has chosen a replacement.
        if (optionalUser.get().isPasswordChangeRequired()) {
            auditLogin(
                    optionalUser.get().getId(),
                    null,
                    "LOGIN_FORCED_PASSWORD_CHANGE",
                    "Password change required"
            );
            throw new ForbiddenOperationException(
                    "A password change is required before signing in."
            );
        }

        // Rechecks account status and lock state under a database lock.
        User authenticatedUser =
                loginAttemptService.recordSuccessfulAttempt(normalizedEmail);

        if (authenticatedUser.isPasswordChangeRequired()) {
            auditLogin(
                    authenticatedUser.getId(),
                    null,
                    "LOGIN_FORCED_PASSWORD_CHANGE",
                    "Password change required"
            );
            throw new ForbiddenOperationException(
                    "A password change is required before signing in."
            );
        }

        String roleName = authenticatedUser.getRole().getName();

        String accessToken = jwtService.generateAccessToken(
                authenticatedUser.getId(),
                authenticatedUser.getEmail(),
                roleName,
                authenticatedUser.getTokenVersion()
        );

        auditLogin(
                authenticatedUser.getId(),
                null,
                "LOGIN_SUCCESS",
                "Signed in"
        );

        return new LoginResponse(
                accessToken,
                "Bearer",
                jwtService.getAccessTokenLifetimeSeconds(),
                authenticatedUser.getId(),
                authenticatedUser.getEmail(),
                roleName
        );
    }

    /**
     * Self-service completion of an administrator-initiated password reset.
     * No bearer token is required: the temporary password is the credential.
     */
    @Transactional
    public void changePassword(
            String email,
            String currentPassword,
            String newPassword,
            String confirmPassword
    ) {
        if (email == null || email.isBlank()
                || currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException(
                    "Email and current password are required."
            );
        }

        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException(
                    "Passwords do not match."
            );
        }

        if (newPassword.getBytes(StandardCharsets.UTF_8).length
                > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new IllegalArgumentException(
                    "Password must not exceed 72 bytes when encoded as UTF-8."
            );
        }

        PasswordPolicy.validate(newPassword);

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            loginAttemptService.recordFailedAttempt(normalizedEmail);
            throw new InvalidCredentialsException();
        }

        user.updatePasswordHash(passwordEncoder.encode(newPassword));
        user.clearPasswordChangeRequired();
        user.unlock();
        user.incrementTokenVersion();

        userRepository.saveAndFlush(user);
        publishSessionRevoked(user.getId());
    }

    @Transactional
    public void logout(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.incrementTokenVersion();
            userRepository.saveAndFlush(user);
            publishSessionRevoked(user.getId());
        });
    }

    private void publishSessionRevoked(UUID userId) {
        if (eventPublisher != null && userId != null) {
            eventPublisher.publishEvent(new WebSocketSessionRevokedEvent(userId));
        }
    }

    /**
     * Records authentication outcomes without writing an email address or any
     * other personal identifier into the audit details.
     */
    private void auditLogin(
            UUID userId,
            String email,
            String action,
            String details
    ) {
        if (auditLogService == null) {
            return;
        }

        try {
            auditLogService.logLogin(userId, email, action, null, details);
        } catch (RuntimeException e) {
            // Auditing must never prevent a legitimate login response.
            org.slf4j.LoggerFactory.getLogger(AuthService.class)
                    .warn("Unable to record login audit event: {}", e.getMessage());
        }
    }
}
