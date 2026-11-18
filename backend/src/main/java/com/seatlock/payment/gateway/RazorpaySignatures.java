package com.seatlock.payment.gateway;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Razorpay's two signatures, both lowercase hex HMAC-SHA256 (no SDK needed, ADR-010 update):
 * <ul>
 *   <li><b>Checkout</b>: {@code HMAC(key_secret, order_id + "|" + payment_id)}, sent by the browser after payment.</li>
 *   <li><b>Webhook</b>: {@code HMAC(webhook_secret, raw request body)} in {@code X-Razorpay-Signature}. Must be
 *       computed over the exact bytes received, before any JSON parsing.</li>
 * </ul>
 * Comparison is constant-time ({@link MessageDigest#isEqual}) so response timing doesn't reveal how many leading
 * characters of a forged signature were right. A blank secret never validates anything.
 */
@Component
public class RazorpaySignatures {

    private static final String HMAC = "HmacSHA256";

    private final String keySecret;
    private final String webhookSecret;

    public RazorpaySignatures(RazorpayProperties properties) {
        this.keySecret = properties.keySecret();
        this.webhookSecret = properties.webhookSecret();
    }

    /** For unit tests: signatures without a Spring context. */
    static RazorpaySignatures of(String keySecret, String webhookSecret) {
        return new RazorpaySignatures(new RazorpayProperties("rzp_test_unit", keySecret, webhookSecret,
                java.net.URI.create("https://api.razorpay.com"), java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(5)));
    }

    public boolean isValidCheckoutSignature(String orderId, String paymentId, String signature) {
        if (orderId == null || paymentId == null) {
            return false;
        }
        return matches(keySecret, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8), signature);
    }

    public boolean isValidWebhookSignature(byte[] rawBody, String signature) {
        return rawBody != null && matches(webhookSecret, rawBody, signature);
    }

    static String hmacHex(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean matches(String secret, byte[] data, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = hmacHex(secret, data).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.trim().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
