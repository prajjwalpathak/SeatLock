package com.seatlock.common.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Background jobs (@Scheduled). Off in the test profile ({@code seatlock.scheduling.enabled=false}) so no
 * job runs in the middle of a test; tests call job methods directly instead.
 * <p>Jobs get their <b>own</b> scheduler ({@code scheduling-N} threads). Without it they ran on the STOMP broker's
 * {@code messageBrokerTaskScheduler} (its TaskScheduler bean makes Boot skip its own; seen as "MessageBroker-2" in
 * the W5·D6 drill logs), so a slow job (DB wait, Razorpay call) could delay WebSocket heart-beats. Not a bean on
 * purpose: a second TaskScheduler bean would make by-type lookups ambiguous.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "seatlock.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig implements SchedulingConfigurer, DisposableBean {

    private final ThreadPoolTaskScheduler jobs = new ThreadPoolTaskScheduler();

    SchedulingConfig() {
        jobs.setPoolSize(2);                              // 5 light jobs; two can overlap (e.g. sweeper + refunds)
        jobs.setThreadNamePrefix("scheduling-");
        jobs.setWaitForTasksToCompleteOnShutdown(true);   // a running batch finishes its transaction on SIGTERM
        jobs.setAwaitTerminationSeconds(10);
        jobs.initialize();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(jobs);
    }

    @Override
    public void destroy() {
        jobs.shutdown();
    }
}
