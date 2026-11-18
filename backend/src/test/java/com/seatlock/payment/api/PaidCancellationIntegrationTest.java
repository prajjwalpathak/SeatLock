package com.seatlock.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import com.seatlock.payment.application.PaidCancellationService;
import com.seatlock.payment.application.PaymentCaptureService;
import com.seatlock.payment.application.RefundProcessor;
import com.seatlock.payment.gateway.GatewayName;
import com.seatlock.payment.support.PaymentFixtures;
import com.seatlock.payment.support.PaymentFixtures.Order;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import testsupport.IntegrationTest;

/** Cancelling a CONFIRMED booking: quote → cancel → REFUNDED + seats back on sale + one refund sent. */
@IntegrationTest
class PaidCancellationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;
    @Autowired PaymentCaptureService capture;
    @Autowired RefundProcessor processor;
    @Autowired PaidCancellationService cancellations;

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
    void aConfirmedBookingIsCancelledRefundedAndItsSeatSoldAgain() throws Exception {
        Order o = confirmed("alice");

        get(o, "/refund-quote")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.percent").value(100))
                .andExpect(jsonPath("$.refundPaise").value(o.amountPaise()))
                .andExpect(jsonPath("$.paidPaise").value(o.amountPaise()));

        post(o, "/refund")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundPaise").value(o.amountPaise()));

        long seat = fx.seatIds().getLast();
        assertThat(jdbc.queryForObject("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?", String.class,
                fx.showId(), seat)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM booking_seats bs JOIN bookings b ON b.id = bs.booking_id
                WHERE b.public_id = ?::uuid AND bs.active""", Integer.class, o.bookingId())).isZero();

        assertThat(processor.processDue()).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT r.status, r.reason, r.amount_paise FROM refunds r JOIN payments p ON p.id = r.payment_id
                WHERE p.gateway_order_id = ?""", o.orderId()))
                .containsEntry("status", "SUBMITTED").containsEntry("reason", "CANCELLATION");
        assertThat(fx.paymentStatus(o.orderId())).isEqualTo("REFUNDED");

        post(o, "/refund").andExpect(status().isOk())
                .andExpect(jsonPath("$.refundPaise").value(o.amountPaise())); // repeating is harmless

        String bob = fx.newUserToken("bob");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/shows/{id}/holds", fx.showId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seat + "]}"))
                .andExpect(status().isCreated()); // back on sale
    }

    @Test
    void unpaidBookingsAreNotCancelledThisWay() throws Exception {
        Order o = fx.mockOrder(fx.newUserToken("alice")); // PAYMENT_PENDING

        get(o, "/refund-quote").andExpect(status().isOk()).andExpect(jsonPath("$.cancellable").value(false));
        post(o, "/refund").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_A_PAID_BOOKING"));
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void someoneElsesBookingIs404() throws Exception {
        Order o = confirmed("alice");
        String bob = fx.newUserToken("bob");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/bookings/{id}/refund", o.bookingId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNotFound());
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
    }

    @Test
    void concurrentCancelsRefundOnce() throws Exception {
        Order o = confirmed("alice");
        long userId = jdbc.queryForObject("SELECT user_id FROM bookings WHERE public_id = ?::uuid", Long.class, o.bookingId());
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PaidCancellationService.CancellationView>> results = new ArrayList<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return cancellations.cancel(userId, o.bookingId());
            }));
        }
        go.countDown();
        for (var f : results) {
            try {
                assertThat(f.get().refundPaise()).isEqualTo(o.amountPaise());
            } catch (Exception e) {
                errors.add(e);
            }
        }
        pool.shutdown();

        assertThat(errors).as("every caller gets the same answer").isEmpty();
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM refunds r JOIN payments p ON p.id = r.payment_id WHERE p.gateway_order_id = ?""",
                Integer.class, o.orderId())).isEqualTo(1);
    }

    private Order confirmed(String name) throws Exception {
        Order o = fx.mockOrder(fx.newUserToken(name));
        capture.capture(GatewayName.MOCK, o.orderId(), "pay_" + UUID.randomUUID().toString().substring(0, 12), o.amountPaise());
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
        return o;
    }

    private ResultActions get(Order o, String suffix) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/bookings/{id}" + suffix, o.bookingId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + o.token()));
    }

    private ResultActions post(Order o, String suffix) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/bookings/{id}" + suffix, o.bookingId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + o.token()));
    }
}
