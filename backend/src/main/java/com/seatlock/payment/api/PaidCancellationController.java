package com.seatlock.payment.api;

import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.identity.security.CurrentUserId;
import com.seatlock.payment.application.PaidCancellationService;
import com.seatlock.payment.application.PaidCancellationService.CancellationView;
import com.seatlock.payment.application.PaidCancellationService.QuoteView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cancelling a paid (CONFIRMED) booking: quote first, then cancel; the refund amount comes from the policy. */
@Tag(name = "Payments")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('USER')")
@RestController
public class PaidCancellationController {

    private final PaidCancellationService cancellations;

    public PaidCancellationController(PaidCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @Operation(summary = "What cancelling my confirmed booking now would refund (policy tiers; 404 unknown/not mine)")
    @GetMapping("/api/v1/bookings/{publicId}/refund-quote")
    public ResponseEntity<QuoteView> quote(@CurrentUserId long userId, @PathVariable String publicId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cancellations.quote(userId, publicId));
    }

    /** Idempotent: cancelling again returns the same refund. */
    @Operation(summary = "Cancel my confirmed booking for a refund: seats go back on sale, refund is queued "
            + "(409 CANCELLATION_CLOSED / NOT_A_PAID_BOOKING; 404 unknown/not mine)")
    @PostMapping("/api/v1/bookings/{publicId}/refund")
    public ResponseEntity<CancellationView> cancel(@CurrentUserId long userId, @PathVariable String publicId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cancellations.cancel(userId, publicId));
    }
}
