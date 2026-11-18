package com.seatlock.payment.application;

import com.seatlock.booking.application.BookingService;
import com.seatlock.booking.application.BookingView;
import com.seatlock.common.exception.InvalidRequestException;
import com.seatlock.common.exception.NotFoundException;
import com.seatlock.payment.application.PaymentCaptureService.CaptureResult;
import com.seatlock.payment.application.PaymentCaptureService.Outcome;
import com.seatlock.payment.domain.Payment;
import com.seatlock.payment.domain.PaymentRepository;
import com.seatlock.payment.gateway.GatewayName;
import com.seatlock.payment.gateway.RazorpaySignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Checkout callback: the browser forwards what Razorpay handed it after a payment. Nothing in it is trusted until
 * the signature {@code HMAC(key_secret, order_id|payment_id)} matches; then the caller must own the booking, and the
 * shared {@link PaymentCaptureService} confirms it (the webhook may already have done so: idempotent).
 */
@Service
public class PaymentVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentVerificationService.class);

    private final RazorpaySignatures signatures;
    private final PaymentRepository payments;
    private final PaymentCaptureService capture;
    private final BookingService bookings;

    public PaymentVerificationService(RazorpaySignatures signatures, PaymentRepository payments,
                                      PaymentCaptureService capture, BookingService bookings) {
        this.signatures = signatures;
        this.payments = payments;
        this.capture = capture;
        this.bookings = bookings;
    }

    public record Verification(Outcome outcome, BookingView booking) {}

    public Verification verifyCheckout(long userId, String orderId, String paymentId, String signature) {
        if (!signatures.isValidCheckoutSignature(orderId, paymentId, signature)) {
            log.warn("Rejected checkout callback for order {}: bad signature", orderId);
            throw new InvalidRequestException("INVALID_PAYMENT_SIGNATURE", "The payment signature is not valid");
        }
        boolean mine = payments.findByGatewayAndGatewayOrderId(GatewayName.RAZORPAY, orderId)
                .map(Payment::getBookingId)
                .filter(bookingId -> bookings.isOwnedBy(bookingId, userId))
                .isPresent();
        if (!mine) {
            throw new NotFoundException("Payment order", orderId); // someone else's order looks like an unknown one
        }

        CaptureResult result = capture.capture(GatewayName.RAZORPAY, orderId, paymentId, null);
        return new Verification(result.outcome(), bookings.getBooking(userId, result.bookingId().toString()));
    }
}
