package com.seatlock.assistant.api;

import com.seatlock.assistant.ai.AiOutcome;
import com.seatlock.assistant.seatfinder.SeatPreferences;
import java.util.List;

/**
 * Result of the AI Seat Finder.
 * <ul>
 *   <li>{@code understood = true}: {@code filters} = what we searched with (to pre-fill the manual form),
 *       {@code assumptions} = what code decided on the user's behalf, {@code result} = the options;</li>
 *   <li>{@code understood = false}: {@code fallbackReason} says why (DISABLED, QUOTA, TIMEOUT, INVALID_OUTPUT...),
 *       the UI falls back to the manual form.</li>
 * </ul>
 */
public record SeatAskResponse(boolean understood, AiOutcome fallbackReason, boolean cached, SeatFinderRequest filters,
                              List<String> assumptions, SeatFinderResponse result) {

    static SeatAskResponse fallback(AiOutcome reason) {
        return new SeatAskResponse(false, reason, false, null, List.of(), null);
    }

    static SeatFinderRequest filtersOf(SeatPreferences p) {
        return new SeatFinderRequest(p.count(), p.maxPricePaise(), List.copyOf(new java.util.TreeSet<>(p.sections())),
                p.aisle(), p.rows());
    }
}
