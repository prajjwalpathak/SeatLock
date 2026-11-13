package com.seatlock.catalog.api;

import com.seatlock.catalog.application.ShowCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shows")
public class ShowController {

    private final ShowCatalogService showCatalogService;

    public ShowController(ShowCatalogService showCatalogService) {
        this.showCatalogService = showCatalogService;
    }

    /** Full seat map (layout, section prices, per-seat price and status) for one show. */
    @GetMapping("/{id}/seats")
    public ShowSeatMapResponse seats(@PathVariable long id) {
        return showCatalogService.getSeatMap(id);
    }
}