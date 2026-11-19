package com.seatlock.realtime;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The only destinations clients may subscribe to: one topic per show. */
public final class SeatTopics {

    private static final Pattern SHOW_SEATS = Pattern.compile("/topic/shows/([1-9][0-9]{0,17})/seats");

    private SeatTopics() {
    }

    public static String forShow(long showId) {
        return "/topic/shows/" + showId + "/seats";
    }

    /** Show id of a valid seat topic, empty for anything else (wildcards, other paths, bad ids). */
    public static OptionalLong showIdOf(String destination) {
        if (destination == null) {
            return OptionalLong.empty();
        }
        Matcher m = SHOW_SEATS.matcher(destination);
        return m.matches() ? OptionalLong.of(Long.parseLong(m.group(1))) : OptionalLong.empty();
    }
}
