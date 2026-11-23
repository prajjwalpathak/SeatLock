package com.seatlock.assistant.api;

import com.seatlock.assistant.seatfinder.RowPreference;
import com.seatlock.assistant.seatfinder.SeatPreferences;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

/**
 * Manual Seat Finder filters. {@code maxPricePaise} is per seat; {@code sections} empty/absent = any;
 * {@code rows} absent = ANY. The upper bound of {@code count} is the hold limit (checked in the service).
 */
public record SeatFinderRequest(
        @NotNull @Min(1) @Max(10) Integer count,
        @Positive Long maxPricePaise,
        @Size(max = 5) List<@NotBlank @Size(max = 16) String> sections,
        Boolean aisle,
        RowPreference rows) {

    public SeatPreferences toPreferences() {
        return new SeatPreferences(count, maxPricePaise, sections == null ? null : new HashSet<>(sections),
                Boolean.TRUE.equals(aisle), rows);
    }
}
