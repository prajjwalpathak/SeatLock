package com.seatlock.payment.gateway;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code seatlock.payments.razorpay.*}. Keys come from {@code RAZORPAY_KEY_ID} / {@code RAZORPAY_KEY_SECRET}
 * (repo-root .env locally, .env.prod on the VM) and are only required when the gateway is {@code razorpay}.
 * The key secret is never logged and never leaves the backend; the key ID is public (Checkout.js needs it).
 */
@Validated
@ConfigurationProperties(prefix = "seatlock.payments.razorpay")
public record RazorpayProperties(String keyId, String keySecret, @NotNull URI baseUrl,
                                 @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {

    static final String TEST_KEY_PREFIX = "rzp_test_";

    /** Fails startup unless a test-mode key pair is configured (this project never takes real money). */
    void requireTestKeys() {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new IllegalStateException(
                    "seatlock.payments.gateway=razorpay needs RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET");
        }
        if (!keyId.startsWith(TEST_KEY_PREFIX)) {
            throw new IllegalStateException("Only Razorpay test-mode keys (" + TEST_KEY_PREFIX + "...) are accepted");
        }
    }

    @Override
    public String toString() { // never print the secret
        return "RazorpayProperties[keyId=" + keyId + ", keySecret=***, baseUrl=" + baseUrl
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
