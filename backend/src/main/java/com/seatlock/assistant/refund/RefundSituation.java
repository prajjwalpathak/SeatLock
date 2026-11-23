package com.seatlock.assistant.refund;

/** Which answer applies to the booking right now (decided in code from its status and the refund policy). */
public enum RefundSituation {
    /** Unpaid (HELD / PAYMENT_PENDING): cancel for free before the payment deadline. */
    UNPAID,
    /** Paid and still cancellable for some refund. */
    PAID_CANCELLABLE,
    /** Paid, but cancellation has closed (too close to the show, or it started). */
    PAID_CLOSED,
    /** Expired unpaid: nothing charged. */
    EXPIRED,
    /** Cancelled before payment: nothing charged. */
    CANCELLED,
    /** Already cancelled with a refund. */
    REFUNDED
}
