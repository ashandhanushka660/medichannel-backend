package com.medichannel.security;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.medichannel.controller.AdminController;
import com.medichannel.dto.admin.AdminUserResponse;
import com.medichannel.entity.Role;
import com.medichannel.entity.User;
import com.medichannel.exception.ConflictException;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.exception.GlobalExceptionHandler;
import com.medichannel.repository.AppointmentRepository;
import com.medichannel.repository.DoctorRepository;
import com.medichannel.repository.PatientRepository;
import com.medichannel.repository.PaymentRepository;
import com.medichannel.repository.RoleRepository;
import com.medichannel.repository.UserRepository;
import com.medichannel.service.AdminService;
import com.medichannel.service.AppointmentService;
import com.medichannel.service.AuditLogService;
import com.medichannel.service.NotificationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAccountStateAuthorizationTests {

    private UserRepository userRepository;
    private DoctorRepository doctorRepository;
    private NotificationService notificationService;
    private AdminService adminService;
    private AdminController adminController;
    private MockMvc mockMvc;

    private Role adminRole;
    private Role managerRole;
    private Role doctorRole;

    private User adminActor;
    private User secondAdmin;
    private User managerActor;
    private User doctorTarget;

    private final UUID adminActorId = UUID.randomUUID();
    private final UUID secondAdminId = UUID.randomUUID();
    private final UUID managerActorId = UUID.randomUUID();
    private final UUID doctorTargetId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        userRepository = mock(UserRepository.class);
        doctorRepository = mock(DoctorRepository.class);
        PatientRepository patientRepository = mock(PatientRepository.class);
        AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        AppointmentService appointmentService = mock(AppointmentService.class);
        notificationService = mock(NotificationService.class);
        PatientDataProtectionService dataProtectionService = mock(PatientDataProtectionService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        adminService = new AdminService(
                userRepository,
                doctorRepository,
                patientRepository,
                appointmentRepository,
                paymentRepository,
                roleRepository,
                appointmentService,
                notificationService,
                dataProtectionService,
                passwordEncoder,
                auditLogService
        );

        adminController = new AdminController(adminService);

        mockMvc = MockMvcBuilders.standaloneSetup(adminController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(org.springframework.security.core.annotation.AuthenticationPrincipal.class)
                                && parameter.getParameterType().equals(Jwt.class);
                    }

                    @Override
                    public Object resolveArgument(
                            org.springframework.core.MethodParameter parameter,
                            org.springframework.web.method.support.ModelAndViewContainer mavContainer,
                            org.springframework.web.context.request.NativeWebRequest webRequest,
                            org.springframework.web.bind.support.WebDataBinderFactory binderFactory
                    ) {
                        jakarta.servlet.http.HttpServletRequest req =
                                webRequest.getNativeRequest(jakarta.servlet.http.HttpServletRequest.class);
                        if (req != null) {
                            for (String name : java.util.Collections.list(req.getAttributeNames())) {
                                Object val = req.getAttribute(name);
                                if (val instanceof org.springframework.security.core.context.SecurityContext sc
                                        && sc.getAuthentication() instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth) {
                                    return jwtAuth.getToken();
                                }
                                if (val instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth) {
                                    return jwtAuth.getToken();
                                }
                                if (val instanceof Jwt jwt) {
                                    return jwt;
                                }
                            }
                            String userId = req.getHeader("X-User-Id");
                            if (userId != null) {
                                String role = req.getHeader("X-User-Role") != null
                                        ? req.getHeader("X-User-Role")
                                        : "MANAGER";
                                return Jwt.withTokenValue("mock-token")
                                        .header("alg", "HS256")
                                        .subject(userId)
                                        .claim("role", role)
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(300))
                                        .build();
                            }
                        }
                        return null;
                    }
                })
                .build();

        adminRole = new Role("ADMIN", "System Administrator");
        managerRole = new Role("MANAGER", "Operations Manager");
        doctorRole = new Role("DOCTOR", "Medical Practitioner");

        adminActor = createUser(adminActorId, adminRole, "admin1@example.com", "ACTIVE");
        secondAdmin = createUser(secondAdminId, adminRole, "admin2@example.com", "ACTIVE");
        managerActor = createUser(managerActorId, managerRole, "manager@example.com", "ACTIVE");
        doctorTarget = createUser(doctorTargetId, doctorRole, "doctor@example.com", "ACTIVE");

        when(userRepository.findById(adminActorId)).thenReturn(Optional.of(adminActor));
        when(userRepository.findById(secondAdminId)).thenReturn(Optional.of(secondAdmin));
        when(userRepository.findById(managerActorId)).thenReturn(Optional.of(managerActor));
        when(userRepository.findById(doctorTargetId)).thenReturn(Optional.of(doctorTarget));

        when(doctorRepository.findByUserId(any(UUID.class))).thenReturn(Optional.empty());
        when(userRepository.findAllByRole_NameOrderByCreatedAtDesc("ADMIN"))
                .thenReturn(List.of(adminActor, secondAdmin));
    }

    private User createUser(UUID id, Role role, String email, String status) throws Exception {
        User user = new User(role, email, "encoded-pass");
        setField(user, "id", id);
        setField(user, "accountStatus", status);
        setField(user, "emailVerified", true);
        setField(user, "failedLoginAttempts", 0);
        return user;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // =========================================================================
    // Core Vulnerability Fix: MANAGER -> ADMIN account state actions are denied
    // =========================================================================

    @Test
    @DisplayName("MANAGER cannot suspend an ADMIN account (Service throws ForbiddenOperationException)")
    void managerCannotSuspendAdminAccount() {
        ForbiddenOperationException exception = assertThrows(
                ForbiddenOperationException.class,
                () -> adminService.suspendUser(managerActorId, adminActorId)
        );
        assertEquals("Managers cannot manage administrator accounts.", exception.getMessage());
    }

    @Test
    @DisplayName("MANAGER cannot disable an ADMIN account (Service throws ForbiddenOperationException)")
    void managerCannotDisableAdminAccount() {
        ForbiddenOperationException exception = assertThrows(
                ForbiddenOperationException.class,
                () -> adminService.disableUser(managerActorId, adminActorId)
        );
        assertEquals("Managers cannot manage administrator accounts.", exception.getMessage());
    }

    @Test
    @DisplayName("MANAGER cannot activate an ADMIN account (Service throws ForbiddenOperationException)")
    void managerCannotActivateAdminAccount() throws Exception {
        setField(adminActor, "accountStatus", "PENDING");

        ForbiddenOperationException exception = assertThrows(
                ForbiddenOperationException.class,
                () -> adminService.activateUser(managerActorId, adminActorId)
        );
        assertEquals("Managers cannot manage administrator accounts.", exception.getMessage());
    }

    @Test
    @DisplayName("MANAGER cannot unlock an ADMIN account (Service throws ForbiddenOperationException)")
    void managerCannotUnlockAdminAccount() throws Exception {
        setField(adminActor, "accountStatus", "LOCKED");
        setField(adminActor, "failedLoginAttempts", 5);
        setField(adminActor, "lockedUntil", Instant.now().plusSeconds(300));

        ForbiddenOperationException exception = assertThrows(
                ForbiddenOperationException.class,
                () -> adminService.unlockUser(managerActorId, adminActorId)
        );
        assertEquals("Managers cannot manage administrator accounts.", exception.getMessage());
    }

    // =========================================================================
    // HTTP Endpoint 403 Forbidden Contract Verification (MockMvc)
    // =========================================================================

    @Test
    @DisplayName("PATCH /api/v1/admin/users/{adminId}/suspend by MANAGER returns HTTP 403")
    void managerSuspendAdminEndpointReturns403() throws Exception {
        mockMvc.perform(
                patch("/api/v1/admin/users/" + adminActorId + "/suspend")
                        .with(jwt().jwt(token -> token.subject(managerActorId.toString()).claim("role", "MANAGER")))
                        .header("X-User-Id", managerActorId.toString())
                        .header("X-User-Role", "MANAGER")
                        .contentType(MediaType.APPLICATION_JSON)
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Managers cannot manage administrator accounts."));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/users/{adminId}/disable by MANAGER returns HTTP 403")
    void managerDisableAdminEndpointReturns403() throws Exception {
        mockMvc.perform(
                patch("/api/v1/admin/users/" + adminActorId + "/disable")
                        .with(jwt().jwt(token -> token.subject(managerActorId.toString()).claim("role", "MANAGER")))
                        .header("X-User-Id", managerActorId.toString())
                        .header("X-User-Role", "MANAGER")
                        .contentType(MediaType.APPLICATION_JSON)
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Managers cannot manage administrator accounts."));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/users/{adminId}/activate by MANAGER returns HTTP 403")
    void managerActivateAdminEndpointReturns403() throws Exception {
        setField(adminActor, "accountStatus", "PENDING");

        mockMvc.perform(
                patch("/api/v1/admin/users/" + adminActorId + "/activate")
                        .with(jwt().jwt(token -> token.subject(managerActorId.toString()).claim("role", "MANAGER")))
                        .header("X-User-Id", managerActorId.toString())
                        .header("X-User-Role", "MANAGER")
                        .contentType(MediaType.APPLICATION_JSON)
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Managers cannot manage administrator accounts."));
    }

    @Test
    @DisplayName("PATCH /api/v1/admin/users/{adminId}/unlock by MANAGER returns HTTP 403")
    void managerUnlockAdminEndpointReturns403() throws Exception {
        setField(adminActor, "accountStatus", "LOCKED");
        setField(adminActor, "failedLoginAttempts", 5);

        mockMvc.perform(
                patch("/api/v1/admin/users/" + adminActorId + "/unlock")
                        .with(jwt().jwt(token -> token.subject(managerActorId.toString()).claim("role", "MANAGER")))
                        .header("X-User-Id", managerActorId.toString())
                        .header("X-User-Role", "MANAGER")
                        .contentType(MediaType.APPLICATION_JSON)
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Managers cannot manage administrator accounts."));
    }

    // =========================================================================
    // Preservation of Permitted Behavior: ADMIN operations on permitted targets
    // =========================================================================

    @Test
    @DisplayName("ADMIN can suspend another administrator when multiple active admins exist")
    void adminCanSuspendAnotherAdmin() {
        AdminUserResponse response = adminService.suspendUser(adminActorId, secondAdminId);
        assertEquals("SUSPENDED", response.accountStatus());
        assertEquals("SUSPENDED", secondAdmin.getAccountStatus());
    }

    @Test
    @DisplayName("ADMIN can disable another administrator when multiple active admins exist")
    void adminCanDisableAnotherAdmin() {
        AdminUserResponse response = adminService.disableUser(adminActorId, secondAdminId);
        assertEquals("DISABLED", response.accountStatus());
        assertEquals("DISABLED", secondAdmin.getAccountStatus());
    }

    @Test
    @DisplayName("ADMIN can activate an inactive account")
    void adminCanActivatePermittedAccount() throws Exception {
        setField(secondAdmin, "accountStatus", "PENDING");
        AdminUserResponse response = adminService.activateUser(adminActorId, secondAdminId);
        assertEquals("ACTIVE", response.accountStatus());
        assertEquals("ACTIVE", secondAdmin.getAccountStatus());
    }

    @Test
    @DisplayName("ADMIN can unlock a locked account")
    void adminCanUnlockPermittedAccount() throws Exception {
        setField(secondAdmin, "accountStatus", "LOCKED");
        setField(secondAdmin, "failedLoginAttempts", 5);
        setField(secondAdmin, "lockedUntil", Instant.now().plusSeconds(300));

        AdminUserResponse response = adminService.unlockUser(adminActorId, secondAdminId);
        assertEquals("ACTIVE", response.accountStatus());
        assertEquals(0, secondAdmin.getFailedLoginAttempts());
        assertNull(secondAdmin.getLockedUntil());
    }

    // =========================================================================
    // Preservation of Permitted Behavior: MANAGER operations on permitted staff
    // =========================================================================

    @Test
    @DisplayName("MANAGER can suspend a permitted non-admin staff member (DOCTOR)")
    void managerCanSuspendDoctor() {
        AdminUserResponse response = adminService.suspendUser(managerActorId, doctorTargetId);
        assertEquals("SUSPENDED", response.accountStatus());
        assertEquals("SUSPENDED", doctorTarget.getAccountStatus());
    }

    @Test
    @DisplayName("MANAGER can disable a permitted non-admin staff member (DOCTOR)")
    void managerCanDisableDoctor() {
        AdminUserResponse response = adminService.disableUser(managerActorId, doctorTargetId);
        assertEquals("DISABLED", response.accountStatus());
        assertEquals("DISABLED", doctorTarget.getAccountStatus());
    }

    @Test
    @DisplayName("MANAGER can activate a permitted non-admin staff member (DOCTOR)")
    void managerCanActivateDoctor() throws Exception {
        setField(doctorTarget, "accountStatus", "PENDING");
        AdminUserResponse response = adminService.activateUser(managerActorId, doctorTargetId);
        assertEquals("ACTIVE", response.accountStatus());
        assertEquals("ACTIVE", doctorTarget.getAccountStatus());
    }

    @Test
    @DisplayName("MANAGER can unlock a permitted non-admin staff member (DOCTOR)")
    void managerCanUnlockDoctor() throws Exception {
        setField(doctorTarget, "accountStatus", "LOCKED");
        setField(doctorTarget, "failedLoginAttempts", 5);
        setField(doctorTarget, "lockedUntil", Instant.now().plusSeconds(300));

        AdminUserResponse response = adminService.unlockUser(managerActorId, doctorTargetId);
        assertEquals("ACTIVE", response.accountStatus());
        assertEquals(0, doctorTarget.getFailedLoginAttempts());
        assertNull(doctorTarget.getLockedUntil());
    }

    // =========================================================================
    // Preservation of Self-Lockout and Last-Admin Protections
    // =========================================================================

    @Test
    @DisplayName("ADMIN cannot suspend own account (preventSelfLockout)")
    void adminCannotSuspendOwnAccount() {
        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> adminService.suspendUser(adminActorId, adminActorId)
        );
        assertEquals("Administrators cannot suspend or disable their own account.", exception.getMessage());
    }

    @Test
    @DisplayName("ADMIN cannot disable own account (preventSelfLockout)")
    void adminCannotDisableOwnAccount() {
        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> adminService.disableUser(adminActorId, adminActorId)
        );
        assertEquals("Administrators cannot suspend or disable their own account.", exception.getMessage());
    }

    @Test
    @DisplayName("ADMIN cannot suspend the last active administrator (preventSelfLockout)")
    void adminCannotSuspendLastActiveAdmin() {
        when(userRepository.findAllByRole_NameOrderByCreatedAtDesc("ADMIN"))
                .thenReturn(List.of(secondAdmin));

        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> adminService.suspendUser(adminActorId, secondAdminId)
        );
        assertEquals("The last active administrator cannot be suspended or disabled.", exception.getMessage());
    }

    @Test
    @DisplayName("ADMIN cannot activate own account when not active")
    void adminCannotActivateOwnAccountWhenNotActive() throws Exception {
        setField(adminActor, "accountStatus", "PENDING");

        ConflictException exception = assertThrows(
                ConflictException.class,
                () -> adminService.activateUser(adminActorId, adminActorId)
        );
        assertEquals("Administrators cannot activate their own account.", exception.getMessage());
    }
}
