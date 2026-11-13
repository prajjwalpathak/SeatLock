package com.seatlock.catalog.api;

import com.seatlock.catalog.domain.EventCategory;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Query parameters for GET /api/v1/events. All filters are optional.
 * {@code q} is free-text search over title, description, category and language
 * (Postgres full-text search with English stemming; any user input is safe).
 * Time filters are UTC instants (the UI converts local dates); a show matches if
 * startsAfter <= starts_at < startsBefore.
 */
public record EventSearchCriteria(
        @Size(max = 100) String q,
        @Size(max = 80) String city,
        EventCategory category,
        @Size(max = 30) String language,
        Instant startsAfter,
        Instant startsBefore,
        @Size(max = 200) String cursor,
        @Min(1) @Max(50) Integer limit) {

    public static final int DEFAULT_LIMIT = 20;

    public EventSearchCriteria {
        q = blankToNull(q);
        city = blankToNull(city);
        language = blankToNull(language);
        cursor = blankToNull(cursor);
        if (limit == null) {
            limit = DEFAULT_LIMIT;
        }
    }

    @AssertTrue(message = "startsBefore must be after startsAfter")
    public boolean isTimeRangeValid() {
        return startsAfter == null || startsBefore == null || startsBefore.isAfter(startsAfter);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}