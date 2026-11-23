package com.medichannel.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import com.medichannel.entity.User;
import com.medichannel.exception.ForbiddenOperationException;
import com.medichannel.repository.UserRepository;

@Service
public class JwtService {

    private static final String ISSUER = "medichannel-api";

    private static final long TOKEN_LIFETIME_MINUTES = 15;

    // WebSocket tickets are single-purpose and very short lived. The
    // browser needs a credential it can put in the STOMP CONNECT frame,
    // but the 15 minute access token must never be exposed to scripts.
    private static final long WS_TICKET_LIFETIME_SECONDS = 60;

    public static final String WS_SCOPE_CLAIM = "scope";
    public static final String WS_SCOPE_VALUE = "ws";
    public static final String WS_JTI_CLAIM = "jti";
    public static final String VERSION_CLAIM = "ver";

    private final JwtEncoder jwtEncoder;
    private final UserRepository userRepository;

    public JwtService(JwtEncoder jwtEncoder, UserRepository userRepository) {
        this.jwtEncoder = jwtEncoder;
        this.userRepository = userRepository;
    }

    public long getAccessTokenLifetimeSeconds() {
        return TOKEN_LIFETIME_MINUTES * 60;
    }

    public long getWebSocketTicketLifetimeSeconds() {
        return WS_TICKET_LIFETIME_SECONDS;
    }

    @Deprecated
    public String generateAccessToken(
            UUID userId,
            String email,
            String role
    ) {
        return generateAccessToken(userId, email, role, 1);
    }

    public String generateAccessToken(
            UUID userId,
            String email,
            String role,
            int tokenVersion
    ) {

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(
                        TOKEN_LIFETIME_MINUTES,
                        ChronoUnit.MINUTES
                ))
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .claim("ver", tokenVersion)
                .build();

        return encode(claims);
    }

    /**
     * Mint a 60 second ticket that is only accepted by the WebSocket
     * handshake. The REST resource server rejects it. The current token
     * version is read from the database so a stale REST session cannot mint
     * a ticket for a revoked account.
     */
    public String generateWebSocketTicket(UUID userId, String role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ForbiddenOperationException(
                        "WebSocket access is not permitted."
                ));

        if (!"ACTIVE".equals(user.getAccountStatus())
                || !user.isEmailVerified()
                || user.isPasswordChangeRequired()) {
            throw new ForbiddenOperationException(
                    "WebSocket access is not permitted."
            );
        }

        return generateWebSocketTicket(userId, role, user.getTokenVersion());
    }

    public String generateWebSocketTicket(
            UUID userId,
            String role,
            int tokenVersion
    ) {

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(WS_TICKET_LIFETIME_SECONDS))
                .subject(userId.toString())
                .claim("role", role)
                .claim(VERSION_CLAIM, tokenVersion)
                .claim(WS_JTI_CLAIM, UUID.randomUUID().toString())
                .claim(WS_SCOPE_CLAIM, WS_SCOPE_VALUE)
                .build();

        return encode(claims);
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}
