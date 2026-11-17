package com.seatlock.payment.application;

import com.seatlock.booking.application.BookingService;
import com.seatlock.booking.application.PayableBooking;
import com.seatlock.common.exception.ServiceUnavailableException;
import com.seatlock.payment.domain.Payment;
import com.seatlock.payment.domain.PaymentRepository;
import com.seatlock.payment.domain.PaymentStatus;
import com.seatlock.payment.gateway.CreateOrderCommand;
import com.seatlock.payment.gateway.GatewayOrder;
import com.seatlock.payment.gateway.PaymentGateway;
import com.seatlock.payment.gateway.PaymentGatewayException;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "Pay now": creates (or returns) the booking's payment order. Talks to the booking module only through
 * {@link BookingService}'s public methods, and to the provider only through {@link PaymentGateway}.
 * <ol>
 *   <li>Read-only checks (owner, state, deadline, server-side amount). An open order already exists → return it (200).</li>
 *   <li>Gateway call <b>outside</b> any transaction: no DB connection or row lock is held during a network call.</li>
 *   <li>One short transaction: lock the booking, re-check, HELD → PAYMENT_PENDING, insert the attempt. If a concurrent
 *       request won meanwhile, its order is returned and ours is left unused at the gateway (unpaid orders cost
 *       nothing and simply expire there).</li>
 * </ol>
 * Naturally idempotent: one live payment per booking (ux_payments_live_booking), so no Idempotency-Key is needed.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Set<PaymentStatus> LIVE = EnumSet.of(PaymentStatus.CREATED, PaymentStatus.CAPTURED);

    private final BookingService bookings;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;

    public PaymentService(BookingService bookings, PaymentRepository payments, PaymentGateway gateway,
                          TransactionTemplate tx) {
        this.bookings = bookings;
        this.payments = payments;
        this.gateway = gateway;
        this.tx = tx;
    }

    /** The order, and whether this call created it (201) or found the open one (200). */
    public record OrderResult(PaymentOrderView order, boolean created) {}

    public OrderResult createOrder(long userId, String bookingPublicId) {
        PayableBooking payable = bookings.checkPayable(userId, bookingPublicId);

        Optional<Payment> live = findLive(payable.bookingId());
        if (live.isPresent()) {
            return new OrderResult(view(live.get(), payable), false);
        }

        GatewayOrder order = callGateway(payable, nextAttempt(payable.bookingId()));

        try {
            return tx.execute(status -> persist(userId, bookingPublicId, order));
        } catch (DataIntegrityViolationException e) {
            // Only reachable if the booking lock was bypassed; the DB index still decides. Answer with the winner.
            return findLive(payable.bookingId())
                    .map(p -> new OrderResult(view(p, payable), false))
                    .orElseThrow(() -> e);
        }
    }

    private OrderResult persist(long userId, String bookingPublicId, GatewayOrder order) {
        PayableBooking locked = bookings.startPayment(userId, bookingPublicId); // row lock until commit

        Optional<Payment> live = findLive(locked.bookingId());
        if (live.isPresent()) {
            log.info("Booking {} already has open order {}; gateway order {} left unused",
                    locked.publicId(), live.get().getGatewayOrderId(), order.orderId());
            return new OrderResult(view(live.get(), locked), false);
        }
        if (order.amountPaise() != locked.amountPaise() || !order.currency().equals(locked.currency())) {
            throw new IllegalStateException("Gateway order " + order.orderId() + " amount doesn't match booking "
                    + locked.publicId());
        }

        Payment payment = payments.saveAndFlush(new Payment(locked.bookingId(), nextAttempt(locked.bookingId()),
                order.gateway(), order.orderId(), order.amountPaise(), order.currency()));
        log.info("Payment order {} ({}) created for booking {}, attempt {}, {} paise",
                order.orderId(), order.gateway(), locked.publicId(), payment.getAttempt(), payment.getAmountPaise());
        return new OrderResult(view(payment, locked), true);
    }

    private GatewayOrder callGateway(PayableBooking payable, int attempt) {
        try {
            return gateway.createOrder(new CreateOrderCommand(receipt(payable, attempt),
                    payable.amountPaise(), payable.currency()));
        } catch (PaymentGatewayException e) {
            log.warn("Payment gateway {} failed for booking {} ({})", gateway.name(), payable.publicId(), e.toString());
            throw new ServiceUnavailableException("PAYMENTS_UNAVAILABLE",
                    "Payments are temporarily unavailable. Please try again in a moment.");
        }
    }

    private Optional<Payment> findLive(long bookingId) {
        return payments.findFirstByBookingIdAndStatusIn(bookingId, LIVE);
    }

    private int nextAttempt(long bookingId) {
        return payments.findTopByBookingIdOrderByAttemptDesc(bookingId).map(p -> p.getAttempt() + 1).orElse(1);
    }

    /** Our reference at the gateway: booking public ID without dashes + attempt (≤ 40 characters). */
    static String receipt(PayableBooking payable, int attempt) {
        return payable.publicId().toString().replace("-", "") + "-" + attempt;
    }

    private static PaymentOrderView view(Payment p, PayableBooking booking) {
        return new PaymentOrderView(booking.publicId(), p.getAttempt(), p.getGateway(), p.getGatewayOrderId(),
                p.getAmountPaise(), p.getCurrency(), p.getStatus(), booking.expiresAt());
    }
}
