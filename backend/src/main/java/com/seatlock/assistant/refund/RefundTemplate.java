package com.seatlock.assistant.refund;

/**
 * The answer without AI: one fixed sentence set per situation, filled from {@link RefundFacts}. Shown when the
 * model is off or unavailable, or when its answer fails {@link AnswerGuard}. Always correct, never creative.
 */
public final class RefundTemplate {

    private RefundTemplate() {
    }

    public static String answer(RefundFacts f) {
        return switch (f.situation()) {
            case UNPAID -> "This booking isn't paid yet, so you can cancel it now at no cost; nothing has been charged. "
                    + "If you don't pay by " + Money.time(f.payBy()) + ", it expires by itself and the seats are released.";
            case PAID_CANCELLABLE -> {
                StringBuilder s = new StringBuilder("You can cancel this booking now and get " + f.percent() + "% back: "
                        + Money.rupees(f.refundPaise()) + " of the " + Money.rupees(f.paidPaise()) + " you paid.");
                if (f.nextChangeAt() != null && f.nextPercent() != null && f.nextPercent() > 0) {
                    s.append(" From ").append(Money.time(f.nextChangeAt())).append(" the refund drops to ")
                            .append(f.nextPercent()).append("% (").append(Money.rupees(f.nextRefundPaise())).append(").");
                } else if (f.nextChangeAt() != null) {
                    s.append(" Cancellation closes at ").append(Money.time(f.nextChangeAt())).append('.');
                }
                s.append(" The refund goes back to your original payment method, usually within 5 to 7 working days.");
                yield s.toString();
            }
            case PAID_CLOSED -> "This booking can no longer be cancelled (" + f.rule() + "), so no refund is possible.";
            case EXPIRED -> "This booking expired before it was paid, so nothing was charged and there is nothing to cancel.";
            case CANCELLED -> "This booking was cancelled before it was paid, so nothing was charged.";
            case REFUNDED -> "This booking has already been cancelled. The refund goes back to your original payment "
                    + "method, usually within 5 to 7 working days.";
        };
    }
}
