package com.seatlock.assistant.api;

import com.seatlock.assistant.seatfinder.SeatFinderService;
import com.seatlock.common.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public like the seat map: suggesting seats changes nothing; holding them needs sign-in. */
@Tag(name = "Seat Finder", description = "Best adjacent seats for a request (manual filters; AI text on W7·D3)")
@RestController
public class SeatFinderController {

    private final SeatFinderService seatFinder;

    public SeatFinderController(SeatFinderService seatFinder) {
        this.seatFinder = seatFinder;
    }

    @Operation(summary = "Up to 3 groups of adjacent available seats matching the filters (holds nothing)")
    @RateLimited("seat-finder")
    @PostMapping("/api/v1/shows/{showId}/seat-finder")
    public ResponseEntity<SeatFinderResponse> find(@PathVariable long showId,
                                                   @Valid @RequestBody SeatFinderRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())   // live statuses, like the seat map
                .body(SeatFinderResponse.from(seatFinder.find(showId, request.toPreferences())));
    }
}
