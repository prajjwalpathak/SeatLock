package com.seatlock.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import com.seatlock.payment.gateway.GatewayName;
import com.seatlock.payment.support.PaymentFixtures;
import com.seatlock.payment.support.PaymentFixtures.Order;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/**
 * W8·D1: the nightly reset refunds only old, fully refundable CONFIRMED bookings of the demo account, through the
 * normal paid-cancellation path, and never touches a booking that is being paid.
 */
@IntegrationTest
class DemoBookingResetIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;
    @Autowired PaymentCaptureService capture;
    @Autowired DemoBookingResetJob job;

    PaymentFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new PaymentFixtures(mvc, jdbc, redis, users, tokenService);
    }

    @AfterEach
    void cleanUp() {
        fx.cleanUp();
    }

    @Test
    void oldConfirmedBookingsAreRefundedRecentAndInFlightOnesAreNot() throws Exception {
        String token = fx.newUserToken("demo");
        Order old = confirmed(token);
        jdbc.update("UPDATE bookings SET confirmed_at = now() - interval '13 hours' WHERE public_id = ?::uuid",
                old.bookingId());
        Order recent = confirmed(token);
        Order paying = fx.mockOrder(token);                          // PAYMENT_PENDING: open order, not paid yet
        long userId = userOf(old);

        assertThat(job.resetBookingsOf(userId)).isEqualTo(1);

        assertThat(fx.bookingStatus(old.bookingId())).isEqualTo("REFUNDED");
        assertThat(fx.bookingStatus(recent.bookingId())).isEqualTo("CONFIRMED");
        assertThat(fx.bookingStatus(paying.bookingId())).isEqualTo("PAYMENT_PENDING");
        assertThat(fx.paymentStatus(paying.orderId())).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM refunds r JOIN payments p ON p.id = r.payment_id
                JOIN bookings b ON b.id = p.booking_id
                WHERE b.public_id = ?::uuid AND r.reason = 'CANCELLATION' AND r.amount_paise = ?
                """, Integer.class, old.bookingId(), old.amountPaise())).isEqualTo(1);   // full refund queued once
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM show_seats ss JOIN booking_seats bs
                  ON bs.show_id = ss.show_id AND bs.seat_id = ss.seat_id
                JOIN bookings b ON b.id = bs.booking_id
                WHERE b.public_id = ?::uuid AND ss.status = 'AVAILABLE'
                """, Integer.class, old.bookingId())).isEqualTo(1);                      // seat back on sale

        assertThat(job.resetBookingsOf(userId)).isZero();          // nothing left: a second run (or instance) is a no-op
    }

    private Order confirmed(String token) throws Exception {
        Order o = fx.mockOrder(token);
        capture.capture(GatewayName.MOCK, o.orderId(), "pay_" + UUID.randomUUID().toString().substring(0, 12), o.amountPaise());
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
        return o;
    }

    private long userOf(Order o) {
        return jdbc.queryForObject("SELECT user_id FROM bookings WHERE public_id = ?::uuid", Long.class, o.bookingId());
    }
}
