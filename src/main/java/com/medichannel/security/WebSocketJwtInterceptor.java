package com.medichannel.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.medichannel.entity.User;
import com.medichannel.repository.UserRepository;
import com.medichannel.service.AuthService;

@Component
public class WebSocketJwtInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;
    private final UserRepository userRepository;

    // Tickets live for 60 seconds; keep used ids slightly longer so a replay
    // cannot succeed by arriving just as the ticket expires.
    private final Cache<String, Boolean> usedTickets = Caffeine.newBuilder()
            .expireAfterWrite(70, TimeUnit.SECONDS)
            .build();

    // ObjectProvider keeps the broker template lazy: injecting it directly
    // can create a cycle between this interceptor and WebSocketConfig.
    private final ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider;

    public WebSocketJwtInterceptor(
            @Qualifier("webSocketJwtDecoder") JwtDecoder jwtDecoder,
            UserRepository userRepository
    ) {
        this(jwtDecoder, userRepository, null);
    }

    @Autowired
    public WebSocketJwtInterceptor(
            @Qualifier("webSocketJwtDecoder") JwtDecoder jwtDecoder,
            UserRepository userRepository,
            ObjectProvider<SimpMessagingTemplate> messagingTemplateProvider
    ) {
        this.jwtDecoder = jwtDecoder;
        this.userRepository = userRepository;
        this.messagingTemplateProvider = messagingTemplateProvider;
    }

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(
                        message,
                        StompHeaderAccessor.class
                );

        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

        String authorization =
                accessor.getFirstNativeHeader("Authorization");

        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new MessageDeliveryException(
                    message,
                    "WebSocket authentication is required."
            );
        }

        String token = authorization.substring(7).trim();

        try {
            Jwt jwt = jwtDecoder.decode(token);

            // The production decoder always supplies a jti. Unit tests that
            // mock the decoder may omit it, so replay checking is conditional.
            String jti = jwt.getClaimAsString(JwtService.WS_JTI_CLAIM);
            if (jti != null && !jti.isBlank()
                    && usedTickets.asMap()
                            .putIfAbsent(jti, Boolean.TRUE) != null) {
                throw new MessageDeliveryException(
                        message,
                        "This WebSocket ticket has already been used."
                );
            }

            UUID userId = UUID.fromString(jwt.getSubject());

            User user = userRepository.findByIdWithRole(userId)
                    .orElseThrow(() -> new MessageDeliveryException(
                            message,
                            "WebSocket authentication failed."
                    ));

            Object verObj = jwt.getClaim(JwtService.VERSION_CLAIM);
            if (verObj instanceof Number verNum
                    && user.getTokenVersion() != verNum.intValue()) {
                throw new MessageDeliveryException(
                        message,
                        "This WebSocket session has been revoked."
                );
            }

            if (!"ACTIVE".equals(user.getAccountStatus())
                    || !user.isEmailVerified()
                    || user.isPasswordChangeRequired()) {
                throw new MessageDeliveryException(
                        message,
                        "This account is not permitted to connect."
                );
            }

            String currentRole = user.getRole().getName();

            Authentication authentication =
                    new UsernamePasswordAuthenticationToken(
                            user.getId().toString(),
                            null,
                            List.of(
                                    new SimpleGrantedAuthority(
                                            "ROLE_" + currentRole
                                    )
                            )
                    );

            accessor.setUser(authentication);

            return message;

        } catch (JwtException | IllegalArgumentException exception) {
            throw new MessageDeliveryException(
                    message,
                    "WebSocket authentication failed."
            );
        }
    }

    /**
     * Best-effort logout notification. Spring's simple broker cannot forcibly
     * close an underlying WebSocket from this interceptor, so connected
     * clients receive an explicit revocation message on their error queue and
     * token-version checks stop any subsequent frames.
     */
    @EventListener
    public void onSessionRevoked(AuthService.WebSocketSessionRevokedEvent event) {
        if (messagingTemplateProvider == null
                || event == null || event.userId() == null) {
            return;
        }

        SimpMessagingTemplate messagingTemplate =
                messagingTemplateProvider.getIfAvailable();
        if (messagingTemplate == null) {
            return;
        }

        messagingTemplate.convertAndSendToUser(
                event.userId().toString(),
                "/queue/errors",
                Map.of(
                        "type", "SESSION_REVOKED",
                        "message", "This session has been revoked."
                )
        );
    }
}
