package no.sjakkportal.live;

import java.util.List;
import no.sjakkportal.config.SjakkportalProperties;
import no.sjakkportal.security.CurrentUser;
import no.sjakkportal.security.TokenAuthenticator;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over a plain WebSocket at {@code /ws}. Clients only subscribe; everything they may change
 * goes through the REST API. The CONNECT frame must carry {@code Authorization: Bearer <access token>}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final TokenAuthenticator authenticator;
    private final SjakkportalProperties properties;

    public WebSocketConfig(TokenAuthenticator authenticator, SjakkportalProperties properties) {
        this.authenticator = authenticator;
        this.properties = properties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
    }

    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler heartbeatScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
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
                if (accessor.getCommand() == StompCommand.CONNECT) {
                    String header = accessor.getFirstNativeHeader("Authorization");
                    if (header == null || !header.startsWith("Bearer ")) {
                        throw new MessageDeliveryException("Authentication required");
                    }
                    CurrentUser user;
                    try {
                        user = authenticator.authenticate(header.substring(7).strip());
                    } catch (RuntimeException e) {
                        throw new MessageDeliveryException("Authentication failed");
                    }
                    accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, List.of()));
                } else if (accessor.getCommand() == StompCommand.SEND) {
                    // Nothing is accepted from clients over the socket.
                    throw new MessageDeliveryException("Read-only connection");
                } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
                    String destination = accessor.getDestination();
                    if (destination == null || !destination.matches("/topic/(tv|games/\\d{1,18}|tournaments/\\d{1,18})")) {
                        throw new MessageDeliveryException("Unknown topic");
                    }
                }
                return message;
            }
        });
    }
}
