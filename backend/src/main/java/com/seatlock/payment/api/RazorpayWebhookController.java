package com.seatlock.payment.api;

import com.seatlock.payment.application.RazorpayWebhookService;
import com.seatlock.payment.application.RazorpayWebhookService.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay → SeatLock. Public URL (no JWT): authenticity comes from {@code X-Razorpay-Signature}, an HMAC of the raw
 * body. The body is taken as bytes so the signature is checked over exactly what was sent. 2xx tells Razorpay to stop
 * retrying, so duplicates and ignored events get 200 too; a bad signature gets 400; a processing error → 500 (retried).
 */
@Tag(name = "Webhooks", description = "Payment provider callbacks (server to server)")
@RestController
public class RazorpayWebhookController {

    private final RazorpayWebhookService webhooks;

    public RazorpayWebhookController(RazorpayWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Operation(summary = "Razorpay webhook (payment.captured, order.paid, payment.failed); HMAC-signed raw body")
    @PostMapping("/api/v1/webhooks/razorpay")
    public ResponseEntity<Void> receive(@RequestBody(required = false) byte[] body,
                                        @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
                                        @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId) {
        Result result = webhooks.handle(body, signature, eventId);
        return result == Result.REJECTED ? ResponseEntity.badRequest().build() : ResponseEntity.ok().build();
    }
}
