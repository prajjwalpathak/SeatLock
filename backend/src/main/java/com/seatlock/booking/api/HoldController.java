package com.seatlock.booking.api;

import com.seatlock.booking.hold.HoldService;
import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.identity.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Seat holds for signed-in users. The user always comes from the token, never from the request. */
@Tag(name = "Holds", description = "Hold seats for a few minutes before paying")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('USER')") // every signed-in role (hierarchy ADMIN ⊃ ORGANIZER ⊃ USER)
@RestController
public class HoldController {

    private final HoldService holds;

    public HoldController(HoldService holds) {
        this.holds = holds;
    }

    @Operation(summary = "Hold 1–6 seats of a show, all or nothing "
            + "(409 SEATS_UNAVAILABLE with seatIds, 409 HOLD_EXISTS, 503 HOLDS_UNAVAILABLE)")
    @PostMapping("/api/v1/shows/{showId}/holds")
    public ResponseEntity<HoldResponse> create(@CurrentUserId long userId, @PathVariable long showId,
                                               @Valid @RequestBody CreateHoldRequest request) {
        HoldResponse hold = HoldResponse.from(holds.createHold(userId, showId, request.seatIds()));
        return ResponseEntity.created(URI.create("/api/v1/holds/" + hold.holdId()))
                .cacheControl(CacheControl.noStore())
                .body(hold);
    }

    @Operation(summary = "My live hold (404 if expired, released or not mine)")
    @GetMapping("/api/v1/holds/{holdId}")
    public ResponseEntity<HoldResponse> get(@CurrentUserId long userId, @PathVariable String holdId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(HoldResponse.from(holds.getHold(userId, holdId)));
    }

    @Operation(summary = "Release my hold; the seats are free again immediately")
    @DeleteMapping("/api/v1/holds/{holdId}")
    public ResponseEntity<Void> release(@CurrentUserId long userId, @PathVariable String holdId) {
        holds.releaseHold(userId, holdId);
        return ResponseEntity.noContent().build();
    }
}
