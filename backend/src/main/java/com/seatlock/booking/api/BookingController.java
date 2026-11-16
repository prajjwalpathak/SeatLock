package com.seatlock.booking.api;

import com.seatlock.booking.application.BookingService;
import com.seatlock.booking.application.BookingService.Conversion;
import com.seatlock.booking.application.BookingView;
import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.identity.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Bookings for signed-in users; the user always comes from the token. */
@Tag(name = "Bookings", description = "Turn a hold into a booking and follow it until payment")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('USER')")
@RestController
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    /**
     * Naturally idempotent (one booking per holdId, enforced by a UNIQUE constraint), so no Idempotency-Key:
     * repeating the call returns the same booking with 200 instead of 201.
     */
    @Operation(summary = "Proceed to pay: turn my live hold into a booking (201 new, 200 already converted; "
            + "404 hold expired/not mine; 409 SEATS_UNAVAILABLE)")
    @PostMapping("/api/v1/holds/{holdId}/booking")
    public ResponseEntity<BookingView> createFromHold(@CurrentUserId long userId, @PathVariable String holdId) {
        Conversion result = bookings.createFromHold(userId, holdId);
        ResponseEntity.BodyBuilder response = result.created()
                ? ResponseEntity.created(URI.create("/api/v1/bookings/" + result.booking().publicId()))
                : ResponseEntity.ok();
        return response.cacheControl(CacheControl.noStore()).body(result.booking());
    }
}
