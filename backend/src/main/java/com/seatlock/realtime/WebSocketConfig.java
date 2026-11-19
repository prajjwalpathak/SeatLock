package com.seatlock.realtime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over a plain WebSocket at {@code /ws} (no SockJS: every supported browser has WebSocket).
 * <p>In-memory "simple" broker for {@code /topic/**} on this instance; W5·D2 adds the Redis Pub/Sub relay
 * so events reach viewers connected to the other instance. Clients only subscribe: there are no
 * application destinations, and SEND frames are rejected (W5·D1 task B).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";

    /** Server → client heart-beats every 10 s keep idle connections alive through Nginx (default 60 s read timeout). */
    static final long[] HEARTBEAT_MS = {10_000, 10_000};

    private final TaskScheduler heartbeatScheduler;

    /** Spring's own broker scheduler; @Lazy because it is created by the same configuration we contribute to. */
    WebSocketConfig(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.heartbeatScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(HEARTBEAT_MS)
                .setTaskScheduler(heartbeatScheduler);
    }
}
