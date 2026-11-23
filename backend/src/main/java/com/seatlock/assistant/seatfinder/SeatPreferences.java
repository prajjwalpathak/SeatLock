package com.seatlock.assistant.seatfinder;

import java.util.Set;

/**
 * What the user asked for: {@code count} seats next to each other in one row. Hard filters: each seat costs at
 * most {@code maxPricePaise} (null = any), its section is in {@code sections} (empty = any), and with
 * {@code aisle} the group includes an aisle seat. {@code rows} only affects the ranking.
 * <p>Built from the manual-filter form today and from the AI-parsed {@code SeatIntent} on W7·D3; same algorithm.</p>
 */
public record SeatPreferences(int count, Long maxPricePaise, Set<String> sections, boolean aisle, RowPreference rows) {

    public SeatPreferences {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }
        if (maxPricePaise != null && maxPricePaise <= 0) {
            throw new IllegalArgumentException("maxPricePaise must be positive");
        }
        sections = sections == null ? Set.of() : Set.copyOf(sections);
        rows = rows == null ? RowPreference.ANY : rows;
    }
}
