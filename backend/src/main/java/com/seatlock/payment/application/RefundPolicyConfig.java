package com.seatlock.payment.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The policy engine is plain Java; this builds it from {@code seatlock.refunds.policy}. */
@Configuration(proxyBeanMethods = false)
public class RefundPolicyConfig {

    @Bean
    RefundPolicyEngine refundPolicyEngine(RefundProperties properties) {
        return new RefundPolicyEngine(properties.policy());
    }
}
