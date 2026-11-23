package com.seatlock.assistant.api;

import com.seatlock.assistant.seatfinder.SeatFinderService;
import com.seatlock.assistant.seatfinder.SeatIntentParser;
import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.identity.security.CurrentUserId;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

/**
 * Manual filters are public like the seat map (suggesting seats changes nothing; holding needs sign-in).
 * The free-text version needs sign-in: it spends the user's AI quota.
 */
@Tag(name = "Seat Finder", description = "Best adjacent seats for a request (manual filters; AI text on W7·D3)")
@RestController
public class SeatFinderController {

    private final SeatFinderService seatFinder;
    private final SeatIntentParser parser;

    public SeatFinderController(SeatFinderService seatFinder, SeatIntentParser parser) {
        this.seatFinder = seatFinder;
        this.parser = parser;
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

    @Operation(summary = "Describe the seats you want in words; AI turns it into filters (fallback: use the manual form)")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @RateLimited("ai")
    @PostMapping("/api/v1/shows/{showId}/seat-finder/ask")
    public ResponseEntity<SeatAskResponse> ask(@CurrentUserId long userId, @PathVariable long showId,
                                               @Valid @RequestBody SeatAskRequest request) {
        // 404 before any AI quota is spent; the model may only use this venue's sections
        var sections = seatFinder.sectionsOf(showId);
        SeatAskResponse body = switch (parser.parse(userId, request.text(), sections, false)) {
            case SeatIntentParser.Outcome.Unavailable u -> SeatAskResponse.fallback(u.reason());
            case SeatIntentParser.Outcome.Parsed p -> new SeatAskResponse(true, null, p.cached(),
                    SeatAskResponse.filtersOf(p.preferences()), p.assumptions(),
                    SeatFinderResponse.from(seatFinder.find(showId, p.preferences())));
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
