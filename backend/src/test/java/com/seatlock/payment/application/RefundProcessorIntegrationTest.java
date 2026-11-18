package com.seatlock.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.booking.application.BookingExpiryService;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import com.seatlock.payment.application.PaymentCaptureService.Outcome;
import com.seatlock.payment.gateway.GatewayName;
import com.seatlock.payment.support.PaymentFixtures;
import com.seatlock.payment.support.PaymentFixtures.Order;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/** Lost and duplicate payments queue refunds in the capture transaction; the processor sends each exactly once. */
@IntegrationTest
class RefundProcessorIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;
    @Autowired PaymentCaptureService capture;
    @Autowired BookingExpiryService expiry;
    @Autowired RefundProcessor processor;

    PaymentFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new PaymentFixtures(mvc, jdbc, redis, users, tokenService);
        jdbc.update("UPDATE refunds SET status = 'FAILED', attempts = 8 WHERE status = 'PENDING'"); // other tests' leftovers
    }

    @AfterEach
    void cleanUp() {
        fx.cleanUp();
    }

    @Test
    void aPaymentAfterExpiryIsRefundedInFullExactlyOnce() throws Exception {
        Order o = expiredWithOrder();

        assertThat(capture.capture(GatewayName.MOCK, o.orderId(), "pay_LATE", o.amountPaise()).outcome())
                .isEqualTo(Outcome.CAPTURED_BOOKING_LOST);
        capture.capture(GatewayName.MOCK, o.orderId(), "pay_LATE", o.amountPaise()); // webhook redelivery
        assertThat(refundsFor(o)).as("queued once, in the capture transaction")
                .singleElement().satisfies(r -> {
                    assertThat(r).containsEntry("status", "PENDING").containsEntry("reason", "LATE_PAYMENT")
                            .containsEntry("gateway_payment_id", "pay_LATE");
                    assertThat(((Number) r.get("amount_paise")).longValue()).isEqualTo(o.amountPaise());
                });

        assertThat(processor.processDue()).isEqualTo(1);
        assertThat(processor.processDue()).as("nothing left to send").isZero();

        Map<String, Object> refund = refundsFor(o).getFirst();
        assertThat(refund.get("status")).isEqualTo("SUBMITTED");
        assertThat((String) refund.get("gateway_refund_id")).startsWith("rfnd_mock_");
        assertThat(fx.paymentStatus(o.orderId())).isEqualTo("REFUNDED");
    }

    @Test
    void aSecondPaymentForAPaidOrderIsRefundedAndTheBookingStaysConfirmed() throws Exception {
        Order o = fx.mockOrder(fx.newUserToken("alice"));
        capture.capture(GatewayName.MOCK, o.orderId(), "pay_FIRST", o.amountPaise());

        assertThat(capture.capture(GatewayName.MOCK, o.orderId(), "pay_SECOND", o.amountPaise()).outcome())
                .isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(processor.processDue()).isEqualTo(1);

        assertThat(refundsFor(o)).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("reason", "DUPLICATE_PAYMENT").containsEntry("gateway_payment_id", "pay_SECOND")
                .containsEntry("status", "SUBMITTED"));
        assertThat(fx.paymentStatus(o.orderId())).as("the first payment is kept").isEqualTo("CAPTURED");
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
    }

    @Test
    void aClaimedRefundIsInvisibleToASecondWorker() throws Exception {
        Order o = expiredWithOrder();
        capture.capture(GatewayName.MOCK, o.orderId(), "pay_LATE", o.amountPaise());

        assertThat(processor.claim()).hasSize(1);
        assertThat(processor.claim()).as("leased: another instance skips it").isEmpty();
    }

    private Order expiredWithOrder() throws Exception {
        Order o = fx.mockOrder(fx.newUserToken("alice"));
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '5 seconds' WHERE public_id = ?::uuid", o.bookingId());
        expiry.expireBatch(Instant.now(), 100);
        return o;
    }

    private java.util.List<Map<String, Object>> refundsFor(Order o) {
        return jdbc.queryForList("""
                SELECT r.* FROM refunds r JOIN payments p ON p.id = r.payment_id WHERE p.gateway_order_id = ?
                ORDER BY r.id""", o.orderId());
    }
}
