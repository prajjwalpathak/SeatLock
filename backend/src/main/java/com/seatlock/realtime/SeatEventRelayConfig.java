package com.seatlock.realtime;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Subscribes this instance to the seat-event channel on its own Redis connection.
 * <p>Messages are handled on <b>one</b> dedicated thread: the container's default executor starts a thread per
 * message, which could forward two events of a show out of order. The executor is deliberately not a bean
 * (an {@code Executor} bean would make Boot drop its own application task executor).</p>
 * <p>Lost subscription (Redis restart): the container re-subscribes every 2 s; events published meanwhile
 * are lost (Pub/Sub keeps nothing), which clients detect as a version gap.</p>
 */
@Configuration(proxyBeanMethods = false)
class SeatEventRelayConfig implements DisposableBean {

    private final ThreadPoolTaskExecutor dispatcher = new ThreadPoolTaskExecutor();

    SeatEventRelayConfig() {
        dispatcher.setCorePoolSize(1);
        dispatcher.setMaxPoolSize(1);
        dispatcher.setQueueCapacity(10_000);   // full → message rejected and logged by the container (= a gap)
        dispatcher.setThreadNamePrefix("seat-relay-");
        dispatcher.initialize();
    }

    @Bean
    RedisMessageListenerContainer seatEventListenerContainer(RedisConnectionFactory connectionFactory,
                                                             SeatEventRelayListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(dispatcher);
        container.setRecoveryInterval(2_000);
        container.addMessageListener(listener, new ChannelTopic(SeatEventRelay.CHANNEL));
        return container;
    }

    @Override
    public void destroy() {
        dispatcher.shutdown();
    }
}
