package com.seatlock.payment.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Cancellation refund rules, pure Java (no Spring, no DB): given what was paid, when the show starts and now, how
 * much goes back. Tiers come from config ({@code seatlock.refunds.policy}); the first tier whose
 * {@code hoursBefore} the cancellation still meets wins, so tiers are checked from the most generous down.
 * Amounts round <b>down</b> to the paisa (never refund more than the policy says). No tier matched, or the show has
 * started → not cancellable.
 */
public final class RefundPolicyEngine {

    /** The answer for one booking at one moment. {@code nextChangeAt}: when the percentage drops next (or null). */
    public record Quote(boolean cancellable, int percent, long refundPaise, Instant nextChangeAt, String rule) {}

    private final List<RefundProperties.Tier> tiers;

    public RefundPolicyEngine(List<RefundProperties.Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("at least one refund tier is required");
        }
        this.tiers = tiers.stream().sorted(Comparator.comparingInt(RefundProperties.Tier::hoursBefore).reversed()).toList();
    }

    public Quote quote(long paidPaise, Instant showStartsAt, Instant now) {
        if (!now.isBefore(showStartsAt)) {
            return new Quote(false, 0, 0, null, "the show has started");
        }
        Duration left = Duration.between(now, showStartsAt);
        Optional<RefundProperties.Tier> tier = tiers.stream()
                .filter(t -> left.compareTo(Duration.ofHours(t.hoursBefore())) >= 0)
                .findFirst();
        if (tier.isEmpty() || tier.get().percent() == 0) {
            return new Quote(false, 0, 0, null, "cancellation closes " + lowestHours() + " h before the show");
        }
        RefundProperties.Tier t = tier.get();
        long refund = Math.multiplyExact(paidPaise, t.percent()) / 100; // floor
        Instant nextChange = showStartsAt.minus(Duration.ofHours(t.hoursBefore()));
        return new Quote(true, t.percent(), refund, nextChange, t.percent() + "% back at least " + t.hoursBefore()
                + " h before the show");
    }

    private int lowestHours() {
        return tiers.stream().filter(t -> t.percent() > 0).mapToInt(RefundProperties.Tier::hoursBefore).min().orElse(0);
    }
}
