package com.seatlock.catalog.api;

import com.seatlock.catalog.application.ShowCatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catalog", description = "Browse events, shows and seat maps")
@RestController
@RequestMapping("/api/v1/shows")
public class ShowController {

    private final ShowCatalogService showCatalogService;

    public ShowController(ShowCatalogService showCatalogService) {
        this.showCatalogService = showCatalogService;
    }

    /**
     * Full seat map (layout, section prices, per-seat price and live status) for one show.
     * {@code no-store}: statuses change every few seconds, so no browser or proxy may reuse a response.
     */
    @GetMapping("/{id}/seats")
    public ResponseEntity<ShowSeatMapResponse> seats(@PathVariable long id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(showCatalogService.getSeatMap(id));
    }
}