package com.seatlock.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs (@Scheduled). Off in the test profile ({@code seatlock.scheduling.enabled=false}) so no
 * job runs in the middle of a test; tests call job methods directly instead.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "seatlock.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
