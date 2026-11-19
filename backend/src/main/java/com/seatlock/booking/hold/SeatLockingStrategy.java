package com.seatlock.booking.hold;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Strategy: where seat holds live and how a seat is claimed. Chosen by {@code seatlock.holds.strategy}:
 * <ul>
 *   <li>{@code redis-hold} (default, {@link RedisHoldStore}): atomic Lua over Redis keys with a TTL;</li>
 *   <li>{@code optimistic} ({@link OptimisticHoldStore}): conditional upserts in Postgres, no Redis on the hold path.</li>
 * </ul>
 * Both give the same contract to {@link HoldService}: all-or-nothing, one live hold per user and show, expiry at
 * {@code expiresAt}, release only by the owning hold. The DB's partial unique index on {@code booking_seats} stays the
 * final guard against overselling either way. W6·D4 benchmarks them against each other.
 */
public interface SeatLockingStrategy {

    /** For logs and metrics: {@code redis-hold} or {@code optimistic}. */
    String name();

    /** All or nothing: either every seat is held under {@code holdId}, or nothing changes. */
    HoldAttempt tryHold(String holdId, long userId, long showId, List<Long> seatIds, Duration ttl, Instant expiresAt);

    /** The live hold, or empty if it never existed or has expired. */
    Optional<HoldRecord> find(String holdId);

    /** Releases the seats still held by this hold; returns how many. */
    int release(HoldRecord hold);

    /** The subset of {@code seatIds} that have a live hold (seat map overlay); one round trip. */
    Set<Long> heldSeatIds(long showId, Collection<Long> seatIds);
}
