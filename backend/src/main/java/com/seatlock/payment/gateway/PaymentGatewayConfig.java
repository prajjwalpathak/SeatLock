package com.seatlock.payment.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exactly one {@link PaymentGateway} bean, chosen by {@code seatlock.payments.gateway} (mock | razorpay);
 * logged at startup. Razorpay needs test-mode keys, checked here so a misconfiguration fails the boot.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentProperties.class, RazorpayProperties.class})
public class PaymentGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfig.class);

    @Bean
    PaymentGateway paymentGateway(PaymentProperties properties, RazorpayProperties razorpay) {
        PaymentGateway gateway = select(properties.gateway(), razorpay);
        log.info("Payment gateway: {}", gateway.name());
        return gateway;
    }

    static PaymentGateway select(GatewayName name, RazorpayProperties razorpay) {
        return switch (name) {
            case MOCK -> new MockGateway();
            case RAZORPAY -> {
                razorpay.requireTestKeys(); // fail at startup, not at the first payment
                yield RazorpayGateway.create(razorpay);
            }
        };
    }
}
