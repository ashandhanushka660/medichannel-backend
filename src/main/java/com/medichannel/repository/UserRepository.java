package com.medichannel.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.medichannel.entity.User;
import com.medichannel.security.DataProtectionService;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * All email equality lookups go through the HMAC lookup column. The
     * public methods keep accepting the plaintext address so existing
     * callers do not need to know about the encryption envelope.
     */
    default Optional<User> findByEmail(String email) {
        return findByEmailLookupHash(DataProtectionService.lookupHashFor(email));
    }

    default boolean existsByEmail(String email) {
        return existsByEmailLookupHash(
                DataProtectionService.lookupHashFor(email)
        );
    }

    Optional<User> findByEmailLookupHash(@Param("emailLookupHash") String emailLookupHash);

    boolean existsByEmailLookupHash(@Param("emailLookupHash") String emailLookupHash);

    List<User> findAllByOrderByCreatedAtDesc();

    List<User> findAllByRole_NameOrderByCreatedAtDesc(String roleName);

    long countByAccountStatus(String accountStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT u
            FROM User u
            JOIN FETCH u.role
            WHERE u.emailLookupHash = :emailLookupHash
            """)
    Optional<User> findByEmailLookupHashForAuthentication(
            @Param("emailLookupHash") String emailLookupHash
    );

    default Optional<User> findByEmailForAuthentication(String email) {
        return findByEmailLookupHashForAuthentication(
                DataProtectionService.lookupHashFor(email)
        );
    }

    @Query("""
            SELECT u
            FROM User u
            JOIN FETCH u.role
            WHERE u.id = :userId
            """)
    Optional<User> findByIdWithRole(
            @Param("userId") UUID userId
    );
}
