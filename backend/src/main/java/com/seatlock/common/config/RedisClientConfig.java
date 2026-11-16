package com.seatlock.common.config;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fail fast while Redis is unreachable.
 * <p>Lettuce's default ({@code DEFAULT} → accept while auto-reconnecting) queues commands during an outage,
 * so every request waits the full command timeout (500 ms) before the caller can fall back. Measured on
 * W3·D1: the seat map took 0.51 s per request with Redis stopped. With {@code REJECT_COMMANDS} a disconnected
 * client throws immediately; auto-reconnect stays on, so service resumes by itself when Redis returns.</p>
 * <p>Limit: this helps when the connection is known to be down (refused/reset). A Redis that hangs with the
 * socket still open is still bounded only by the 500 ms timeout.</p>
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

    @Bean
    LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
