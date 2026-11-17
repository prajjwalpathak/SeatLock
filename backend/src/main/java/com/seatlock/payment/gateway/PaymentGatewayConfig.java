package com.seatlock.payment.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exactly one {@link PaymentGateway} bean, chosen by {@code seatlock.payments.gateway}; logged at startup. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfig.class);

    @Bean
    PaymentGateway paymentGateway(PaymentProperties properties) {
        PaymentGateway gateway = select(properties.gateway());
        log.info("Payment gateway: {}", gateway.name());
        return gateway;
    }

    static PaymentGateway select(GatewayName name) {
        return switch (name) {
            case MOCK -> new MockGateway();
            case RAZORPAY -> throw new IllegalStateException(
                    "seatlock.payments.gateway=razorpay is not available yet (W4·D2); use mock");
        };
    }
}
