package com.workflow.config;

import com.workflow.auth.service.JwtService;
import com.workflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(parseAllowedOrigins().toArray(String[]::new))
                .withSockJS()
                .setSessionCookieNeeded(false);
    }

    private List<String> parseAllowedOrigins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .collect(Collectors.toList());
    }

    private String resolveBearerToken(StompHeaderAccessor accessor) {
        String token = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if ((token == null || token.isBlank()) && accessor.getNativeHeader("authorization") != null) {
            token = accessor.getFirstNativeHeader("authorization");
        }
        if (token != null && token.startsWith("Bearer ")) {
            return token.substring(7);
        }
        return token;
    }

    private boolean requiresAuthentication(StompCommand command) {
        return StompCommand.CONNECT.equals(command)
                || StompCommand.SEND.equals(command)
                || StompCommand.SUBSCRIBE.equals(command);
    }

    private UsernamePasswordAuthenticationToken resolveAuthentication(String token) {
        if (token == null || token.isBlank() || !jwtService.isTokenValid(token)) {
            return null;
        }
        String userId = jwtService.extractUserId(token);
        return userRepository.findById(userId)
                .map(user -> new UsernamePasswordAuthenticationToken(
                        user,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                ))
                .orElse(null);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }

                if (!requiresAuthentication(accessor.getCommand())) {
                    return message;
                }

                if (accessor.getUser() != null) {
                    return message;
                }

                UsernamePasswordAuthenticationToken authentication = resolveAuthentication(resolveBearerToken(accessor));
                if (authentication == null) {
                    throw new AccessDeniedException("JWT invalido o ausente en WebSocket");
                }
                accessor.setUser(authentication);
                return message;
            }
        });
    }
}
