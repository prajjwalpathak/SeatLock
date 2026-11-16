package com.seatlock.booking.hold;

/**
 * Redis key schema for seat holds. One key per held seat:
 * <pre>hold:show:{showId}:seat:{seatId} → holdId   (PX = hold TTL)</pre>
 * <ul>
 *   <li>The key's TTL is the source of truth for "held": when it expires, the seat is free again.
 *       No sweeper is needed for Redis itself; Redis returns nil for an expired key on access.</li>
 *   <li>The value is the {@code holdId}, so a later confirm/release can check it still owns the seat
 *       (plan: holdId check instead of fencing tokens).</li>
 *   <li>Keys are built from numeric IDs only, so no user input ever reaches a key.</li>
 * </ul>
 * Single Redis node: no cluster hash tags. With Redis Cluster, the multi-key Lua script (W3·D2) would
 * need {@code hold:{show:42}:seat:7} so all seats of a show share one slot.
 */
public final class HoldKeys {

    public static final String PREFIX = "hold:show:";

    private HoldKeys() {}

    public static String seatKey(long showId, long seatId) {
        return PREFIX + showId + ":seat:" + seatId;
    }
}
