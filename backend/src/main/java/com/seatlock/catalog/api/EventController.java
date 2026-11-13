package com.seatlock.catalog.api;

import com.seatlock.catalog.application.EventCatalogService;
import com.seatlock.common.web.CursorPage;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventCatalogService catalogService;

    public EventController(EventCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Published events with at least one upcoming show matching the filters, soonest first.
     * Example: /api/v1/events?city=Mumbai&category=CONCERT&limit=10&cursor=...
     */
    @GetMapping
    public CursorPage<EventSummaryResponse> search(@Valid EventSearchCriteria criteria) {
        return catalogService.search(criteria);
    }

    /** Event detail with upcoming shows. 404 for unknown, draft or cancelled events. */
    @GetMapping("/{id}")
    public EventDetailResponse get(@PathVariable long id) {
        return catalogService.getEvent(id);
    }
}