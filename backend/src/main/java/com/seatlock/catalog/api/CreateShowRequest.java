package com.seatlock.catalog.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Map;

/**
 * A new show of one of the organizer's events.
 *
 * @param startsAt           UTC instant (the UI converts from IST)
 * @param sectionPricesPaise one price per section of the venue, e.g. {"PLATINUM": 150000, "GOLD": 100000, "SILVER": 60000};
 *                           every seat in a section gets that price. Must name exactly the venue's sections.
 */
public record CreateShowRequest(
        @NotNull Long venueId,
        @NotNull Instant startsAt,
        @NotEmpty Map<String, @NotNull @Positive @Max(MAX_PRICE_PAISE) Long> sectionPricesPaise) {

    /** ₹1,00,000 per seat: a sanity cap against typos (paise vs rupees). */
    public static final long MAX_PRICE_PAISE = 10_000_000L;
}
