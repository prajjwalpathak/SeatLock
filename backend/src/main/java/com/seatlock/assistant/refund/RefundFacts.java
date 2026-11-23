package com.seatlock.assistant.refund;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the assistant may say about one booking, computed by code ({@code RefundPolicyEngine} + booking state).
 * The model gets {@link #lines()} verbatim and may only repeat these amounts, percentages and times
 * ({@link AnswerGuard} enforces amounts and percentages).
 *
 * @param paidPaise       captured payment (paid bookings), else null
 * @param refundPaise     what cancelling now refunds (PAID_CANCELLABLE), else null
 * @param nextChangeAt    when the refund drops to the next tier (PAID_CANCELLABLE), or null
 * @param nextPercent     percentage after {@code nextChangeAt}; 0 = cancellation closes then
 * @param payBy           payment deadline (UNPAID), else null
 * @param rule            the engine's reason text (e.g. "cancellation closes 24 h before the show")
 */
public record RefundFacts(RefundSituation situation, long totalPaise, Long paidPaise, int percent, Long refundPaise,
                          Instant nextChangeAt, Integer nextPercent, Long nextRefundPaise, Instant showStartsAt,
                          Instant payBy, String rule, Set<Integer> policyPercents) {

    public RefundFacts {
        policyPercents = Set.copyOf(policyPercents);
    }

    /** Amounts (paise) an answer may mention. */
    public Set<Long> allowedAmounts() {
        Set<Long> amounts = new LinkedHashSet<>();
        amounts.add(totalPaise);
        if (paidPaise != null) {
            amounts.add(paidPaise);
        }
        if (refundPaise != null) {
            amounts.add(refundPaise);
        }
        if (nextRefundPaise != null) {
            amounts.add(nextRefundPaise);
        }
        return amounts;
    }

    /** Percentages an answer may mention: this booking's, plus the policy's tiers (excerpts quote them). */
    public Set<Integer> allowedPercents() {
        Set<Integer> p = new LinkedHashSet<>(policyPercents);
        p.add(percent);
        if (nextPercent != null) {
            p.add(nextPercent);
        }
        p.add(0);
        p.add(100);
        return p;
    }

    /** The facts as plain sentences for the prompt (amounts and times already formatted). */
    public List<String> lines() {
        List<String> l = new ArrayList<>();
        l.add("Show starts: " + Money.time(showStartsAt) + ".");
        switch (situation) {
            case UNPAID -> {
                l.add("The booking is NOT paid yet. Total due: " + Money.rupees(totalPaise) + ". Nothing has been charged.");
                l.add("It can be cancelled now at no cost. If it is not paid by " + Money.time(payBy)
                        + ", it expires by itself and the seats are released.");
            }
            case PAID_CANCELLABLE -> {
                l.add("The booking is paid: " + Money.rupees(paidPaise) + ".");
                l.add("Cancelling NOW refunds " + percent + "%: " + Money.rupees(refundPaise) + ".");
                if (nextChangeAt != null && nextPercent != null && nextPercent > 0) {
                    l.add("From " + Money.time(nextChangeAt) + " the refund drops to " + nextPercent + "%: "
                            + Money.rupees(nextRefundPaise) + ".");
                } else if (nextChangeAt != null) {
                    l.add("Cancellation closes at " + Money.time(nextChangeAt) + "; after that there is no refund.");
                }
                l.add("Refunds go to the original payment method, usually within 5 to 7 working days. Only the whole booking can be cancelled.");
            }
            case PAID_CLOSED -> {
                l.add("The booking is paid: " + Money.rupees(paidPaise) + ".");
                l.add("It can NOT be cancelled any more (" + rule + "). No refund is possible.");
            }
            case EXPIRED -> l.add("The booking expired before it was paid. Nothing was charged; there is nothing to cancel.");
            case CANCELLED -> l.add("The booking was cancelled before payment. Nothing was charged.");
            case REFUNDED -> l.add("The booking has already been cancelled and its refund was started; refunds reach the original payment method, usually within 5 to 7 working days.");
        }
        return l;
    }
}
