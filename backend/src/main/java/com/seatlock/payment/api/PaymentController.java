package com.seatlock.payment.api;

import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.identity.security.CurrentUserId;
import com.seatlock.payment.application.PaymentOrderView;
import com.seatlock.payment.application.PaymentService;
import com.seatlock.payment.application.PaymentService.OrderResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payment orders for the signed-in user's bookings. No request body: the amount is always computed on the server. */
@Tag(name = "Payments", description = "Create the payment order for a booking")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('USER')")
@RestController
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * Naturally idempotent (one open order per booking), so no Idempotency-Key: repeating the call returns the
     * same order with 200 instead of 201.
     */
    @Operation(summary = "Pay now: create my booking's payment order (201 new, 200 open order returned; "
            + "404 unknown/not mine; 409 BOOKING_EXPIRED or INVALID_BOOKING_TRANSITION; 503 PAYMENTS_UNAVAILABLE)",
            description = "No request body: the amount is computed on the server from the booking's seats. Open Razorpay "
                    + "Checkout with `keyId` (public key ID; null for the mock gateway) and `orderId`; close it by `payBy`. "
                    + "The checkout success callback is confirmed server-side (signature verification).")
    @PostMapping("/api/v1/bookings/{publicId}/payment-order")
    public ResponseEntity<PaymentOrderView> createOrder(@CurrentUserId long userId, @PathVariable String publicId) {
        OrderResult result = payments.createOrder(userId, publicId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(result.order());
    }
}
