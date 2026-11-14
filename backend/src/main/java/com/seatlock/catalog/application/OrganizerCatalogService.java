package com.seatlock.catalog.application;

import com.seatlock.catalog.api.OrganizerEventResponse;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organizer-side catalog operations (create show arrives on W2·D4). */
@Service
@Transactional(readOnly = true)
public class OrganizerCatalogService {

    private final OrganizerEventQueryRepository queries;
    private final Clock clock;

    public OrganizerCatalogService(OrganizerEventQueryRepository queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    /** Only the caller's own events: ownership comes from the token, never from a request parameter. */
    public List<OrganizerEventResponse> myEvents(long organizerId) {
        return queries.eventsOf(organizerId, clock.instant());
    }
}
