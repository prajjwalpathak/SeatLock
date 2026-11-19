package com.seatlock.realtime;

import com.seatlock.common.config.CorsProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over a plain WebSocket at {@code /ws} (no SockJS: every supported browser has WebSocket).
 * <p>In-memory "simple" broker for {@code /topic/**} on this instance; W5·D2 adds the Redis Pub/Sub relay
 * so events reach viewers connected to the other instance. Clients only subscribe: there are no
 * application destinations, and SEND frames are rejected ({@link StompAuthChannelInterceptor}).</p>
 * <p>The handshake accepts only the CORS allow-list's origins (403 otherwise), so other sites can't open
 * connections from a visitor's browser. Requests without an Origin header (non-browser clients) pass;
 * they could connect anyway, and every frame still goes through the interceptor.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";

    /** Server → client heart-beats every 10 s keep idle connections alive through Nginx (default 60 s read timeout). */
    static final long[] HEARTBEAT_MS = {10_000, 10_000};

    private final TaskScheduler heartbeatScheduler;
    private final CorsProperties cors;
    private final StompAuthChannelInterceptor authInterceptor;
    private final StompErrorHandler errorHandler;

    /** Spring's own broker scheduler; @Lazy because it is created by the same configuration we contribute to. */
    WebSocketConfig(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler,
                    CorsProperties cors, StompAuthChannelInterceptor authInterceptor, StompErrorHandler errorHandler) {
        this.heartbeatScheduler = messageBrokerTaskScheduler;
        this.cors = cors;
        this.authInterceptor = authInterceptor;
        this.errorHandler = errorHandler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT)
                .setAllowedOrigins(cors.allowedOrigins().toArray(String[]::new)); // exact origins, never "*"
        registry.setErrorHandler(errorHandler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(HEARTBEAT_MS)
                .setTaskScheduler(heartbeatScheduler);
    }
}
