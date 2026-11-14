package com.seatlock.catalog.api;

import com.seatlock.catalog.application.OrganizerCatalogService;
import com.seatlock.common.config.OpenApiConfig;
import com.seatlock.identity.security.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Organizer", description = "Endpoints for event organizers")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasRole('ORGANIZER')")
@RestController
@RequestMapping("/api/v1/organizer/events")
public class OrganizerEventController {

    private final OrganizerCatalogService organizerService;

    public OrganizerEventController(OrganizerCatalogService organizerService) {
        this.organizerService = organizerService;
    }

    @Operation(summary = "My events incl. drafts, newest first (ORGANIZER)")
    @GetMapping
    public List<OrganizerEventResponse> myEvents(@CurrentUserId long organizerId) {
        return organizerService.myEvents(organizerId);
    }

    @Operation(summary = "Schedule a show of my event with one price per section (ORGANIZER; 409 SHOW_OVERLAP)")
    @PostMapping("/{eventId}/shows")
    public ResponseEntity<CreatedShowResponse> createShow(@CurrentUserId long organizerId, @PathVariable long eventId,
                                                          @Valid @RequestBody CreateShowRequest request) {
        CreatedShowResponse created = organizerService.createShow(organizerId, eventId, request);
        return ResponseEntity.created(URI.create("/api/v1/shows/" + created.showId() + "/seats")).body(created);
    }
}
