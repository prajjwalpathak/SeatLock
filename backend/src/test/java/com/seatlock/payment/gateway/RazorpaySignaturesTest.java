package com.seatlock.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Fixed vectors computed independently (Python hmac/hashlib), so the test doesn't just mirror the code. */
class RazorpaySignaturesTest {

    static final String CHECKOUT_SIG = "761688dbebb68646172d058cd7dfe57119e04d6b3153ae94781d44384809a2f6";
    static final String WEBHOOK_BODY = "{\"event\":\"payment.captured\"}";
    static final String WEBHOOK_SIG = "5453ee3e65a2636c292bbe6eb21aca81e89ee72259a164017377249352dddb8f";

    private final RazorpaySignatures signatures = RazorpaySignatures.of("test-key-secret", "test-webhook-secret");

    @Test
    void checkoutSignatureIsHmacOfOrderPipePayment() {
        assertThat(signatures.isValidCheckoutSignature("order_TEST000000001", "pay_TEST000000001", CHECKOUT_SIG)).isTrue();
    }

    @Test
    void anyChangeBreaksTheCheckoutSignature() {
        assertThat(signatures.isValidCheckoutSignature("order_TEST000000001", "pay_TEST000000002", CHECKOUT_SIG)).isFalse();
        assertThat(signatures.isValidCheckoutSignature("order_TEST000000002", "pay_TEST000000001", CHECKOUT_SIG)).isFalse();
        assertThat(signatures.isValidCheckoutSignature("order_TEST000000001", "pay_TEST000000001",
                CHECKOUT_SIG.replace('6', '7'))).isFalse();
        assertThat(signatures.isValidCheckoutSignature("order_TEST000000001", "pay_TEST000000001", "")).isFalse();
        assertThat(signatures.isValidCheckoutSignature(null, "pay_TEST000000001", CHECKOUT_SIG)).isFalse();
    }

    @Test
    void webhookSignatureIsHmacOfTheExactRawBody() {
        byte[] body = WEBHOOK_BODY.getBytes(StandardCharsets.UTF_8);
        assertThat(signatures.isValidWebhookSignature(body, WEBHOOK_SIG)).isTrue();

        byte[] reformatted = "{ \"event\": \"payment.captured\" }".getBytes(StandardCharsets.UTF_8);
        assertThat(signatures.isValidWebhookSignature(reformatted, WEBHOOK_SIG))
                .as("same JSON, different bytes: must fail (verify before parsing)").isFalse();
    }

    @Test
    void theSecretsAreNotInterchangeable() {
        assertThat(signatures.isValidWebhookSignature("order_TEST000000001|pay_TEST000000001"
                .getBytes(StandardCharsets.UTF_8), CHECKOUT_SIG)).isFalse();
    }

    @Test
    void aBlankSecretNeverValidates() {
        RazorpaySignatures unconfigured = RazorpaySignatures.of("", null);
        String sigOfEmptyKey = RazorpaySignatures.hmacHex(" ", WEBHOOK_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(unconfigured.isValidWebhookSignature(WEBHOOK_BODY.getBytes(StandardCharsets.UTF_8), sigOfEmptyKey))
                .isFalse();
        assertThat(unconfigured.isValidCheckoutSignature("order_TEST000000001", "pay_TEST000000001", CHECKOUT_SIG))
                .isFalse();
    }
}
