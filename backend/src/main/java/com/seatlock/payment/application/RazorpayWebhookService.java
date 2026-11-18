package com.seatlock.payment.application;

import com.seatlock.payment.application.PaymentCaptureService.CaptureResult;
import com.seatlock.payment.domain.PaymentRepository;
import com.seatlock.payment.gateway.GatewayName;
import com.seatlock.payment.gateway.RazorpaySignatures;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Razorpay webhooks (at-least-once delivery). Order of checks:
 * <ol>
 *   <li>HMAC of the <b>raw</b> body with the webhook secret, before parsing anything (bad → 400, nothing stored).</li>
 *   <li>One transaction: claim the event id in {@code webhook_events} (duplicate → skip), then process. If processing
 *       throws, the claim rolls back with it, so Razorpay's retry is processed again.</li>
 * </ol>
 * {@code payment.captured} / {@code order.paid} confirm through the same {@link PaymentCaptureService} as the checkout
 * callback. {@code payment.failed} is recorded (handled on W4·D4); other events are acknowledged and ignored.
 */
@Service
public class RazorpayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);

    public enum Result { PROCESSED, DUPLICATE, IGNORED, REJECTED }

    private final RazorpaySignatures signatures;
    private final PaymentCaptureService capture;
    private final PaymentRepository payments;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    public RazorpayWebhookService(RazorpaySignatures signatures, PaymentCaptureService capture,
                                  PaymentRepository payments, JdbcClient jdbc, TransactionTemplate tx,
                                  JsonMapper json, Clock clock) {
        this.signatures = signatures;
        this.capture = capture;
        this.payments = payments;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    public Result handle(byte[] rawBody, String signature, String eventIdHeader) {
        if (!signatures.isValidWebhookSignature(rawBody, signature)) {
            log.warn("Rejected Razorpay webhook: bad or missing signature ({} bytes)", rawBody == null ? 0 : rawBody.length);
            return Result.REJECTED;
        }
        JsonNode root;
        try {
            root = json.readTree(rawBody);
        } catch (JacksonException e) {
            log.warn("Signed Razorpay webhook is not JSON; ignored");
            return Result.IGNORED;
        }
        String eventType = root.path("event").asString("");
        String bodyHash = sha256(rawBody);
        String eventId = eventIdHeader == null || eventIdHeader.isBlank() ? "sha256:" + bodyHash : eventIdHeader.trim();

        return tx.execute(status -> {
            if (!claim(eventId, eventType, bodyHash)) {
                log.info("Razorpay webhook {} ({}) already processed; skipped", eventId, eventType);
                return Result.DUPLICATE;
            }
            String outcome = process(eventType, root);
            jdbc.sql("UPDATE webhook_events SET outcome = :outcome WHERE provider = 'RAZORPAY' AND event_id = :id")
                    .param("outcome", outcome).param("id", eventId).update();
            log.info("Razorpay webhook {} ({}): {}", eventId, eventType, outcome);
            return outcome.startsWith("IGNORED") ? Result.IGNORED : Result.PROCESSED;
        });
    }

    /** INSERT ... ON CONFLICT DO NOTHING: true if this delivery is the first with this id. */
    private boolean claim(String eventId, String eventType, String bodyHash) {
        return jdbc.sql("""
                        INSERT INTO webhook_events (provider, event_id, event_type, payload_sha256, outcome, received_at)
                        VALUES ('RAZORPAY', :id, :type, :hash, 'RECEIVED', :now)
                        ON CONFLICT (provider, event_id) DO NOTHING
                        """)
                .param("id", eventId)
                .param("type", eventType.length() > 64 ? eventType.substring(0, 64) : eventType)
                .param("hash", bodyHash)
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .update() == 1;
    }

    private String process(String eventType, JsonNode root) {
        return switch (eventType) {
            case "payment.captured", "order.paid" -> capture(root);
            case "payment.failed" -> "RECORDED_FAILED_PAYMENT"; // W4·D4 marks the attempt FAILED
            default -> "IGNORED_EVENT_TYPE";
        };
    }

    private String capture(JsonNode root) {
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String orderId = text(payment, "order_id").orElse(null);
        String paymentId = text(payment, "id").orElse(null);
        if (orderId == null || paymentId == null) {
            return "IGNORED_NO_ORDER";
        }
        // Checked before capture(): an exception inside its (joined) transaction would roll back the claim too.
        // existsBy... loads no entity, so capture()'s locked read is the first time this Payment enters the context.
        if (!payments.existsByGatewayAndGatewayOrderId(GatewayName.RAZORPAY, orderId)) {
            return "IGNORED_UNKNOWN_ORDER"; // e.g. an order from another environment on the same Razorpay account
        }
        Long amount = payment.path("amount").isNumber() ? payment.path("amount").asLong() : null;
        CaptureResult result = capture.capture(GatewayName.RAZORPAY, orderId, paymentId, amount);
        return result.outcome().name();
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? Optional.of(value.asString()) : Optional.empty();
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
