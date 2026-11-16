package com.seatlock.common.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Bucket4j on the app's own Lettuce client (same host, timeouts and fail-fast options as everything else;
 * see RedisClientConfig). The proxy manager is built on first use: the connection factory only creates its
 * native client when the application context starts. Buckets expire from Redis shortly after they'd be full
 * again, so idle keys cost nothing.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    RateLimiter rateLimiter(LettuceConnectionFactory connectionFactory) {
        return new RateLimiter(() -> Bucket4jLettuce
                .casBasedBuilder((RedisClient) connectionFactory.getRequiredNativeClient()) // standalone Redis
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .build());
    }
}
