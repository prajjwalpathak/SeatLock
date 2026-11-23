package com.seatlock.assistant.seatfinder;

import java.util.List;

/**
 * What the model extracts from a free-text seat request (its JSON reply, after our validation). It only
 * <i>interprets</i>: {@code budgetRupees} is the number the user said, {@code budgetIsTotal} whether it was for
 * all seats; the per-seat amount in paise is computed by {@link SeatIntentParser}, never by the model.
 * Null {@code count} = not stated.
 */
public record SeatIntent(Integer count, Integer budgetRupees, Boolean budgetIsTotal, List<String> sections,
                         Boolean aisle, String rows) {}
