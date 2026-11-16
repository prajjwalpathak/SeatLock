package com.seatlock.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import testsupport.IntegrationTest;

/** The customizer actually reaches the auto-configured connection factory (and keeps Boot's timeouts). */
@IntegrationTest
class RedisClientConfigIntegrationTest {

    @Autowired LettuceConnectionFactory connectionFactory;

    @Test
    void commandsAreRejectedInsteadOfQueuedWhileDisconnected() {
        ClientOptions options = connectionFactory.getClientConfiguration().getClientOptions().orElseThrow();

        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(options.isAutoReconnect()).isTrue();
    }
}
