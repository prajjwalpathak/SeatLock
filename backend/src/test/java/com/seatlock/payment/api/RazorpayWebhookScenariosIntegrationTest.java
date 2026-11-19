package com.seatlock.payment.api;

import static com.seatlock.payment.support.PaymentFixtures.webhookBody;
import static com.seatlock.payment.support.PaymentFixtures.webhookSignature;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import com.seatlock.payment.support.PaymentFixtures;
import com.seatlock.payment.support.PaymentFixtures.Order;
import java.util.ArrayList;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/**
 * What Razorpay actually does to a webhook endpoint: events out of order, a late failure report for an earlier try,
 * the same delivery many times at once, and a redelivery after we answered 500.
 */
@IntegrationTest
class RazorpayWebhookScenariosIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;

    PaymentFixtures fx;
    final List<String> eventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fx = new PaymentFixtures(mvc, jdbc, redis, users, tokenService);
    }

    @AfterEach
    void cleanUp() {
        fx.cleanUp();
        eventIds.forEach(id -> jdbc.update("DELETE FROM webhook_events WHERE event_id = ?", id));
    }

    @Test
    void orderPaidBeforePaymentCapturedConfirmsOnce() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));

        String first = deliver(webhookBody("order.paid", o.orderId(), "pay_OOO000000001", o.amountPaise()), 200);
        String second = deliver(webhookBody("payment.captured", o.orderId(), "pay_OOO000000001", o.amountPaise()), 200);

        assertThat(outcome(first)).isEqualTo("CONFIRMED");
        assertThat(outcome(second)).isEqualTo("ALREADY_DONE");
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
    }

    @Test
    void aLateFailureReportForAnEarlierTryChangesNothing() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        deliver(webhookBody("payment.captured", o.orderId(), "pay_OK0000000001", o.amountPaise()), 200);

        String failed = deliver("""
                {"entity":"event","event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_EARLY00000001",
                 "amount":%d,"currency":"INR","status":"failed","order_id":"%s","error_description":"bank declined"}}}}"""
                .formatted(o.amountPaise(), o.orderId()), 200);

        assertThat(outcome(failed)).isEqualTo("RECORDED_FAILED_ATTEMPT");
        assertThat(fx.paymentStatus(o.orderId())).isEqualTo("CAPTURED");
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM payments WHERE gateway_order_id = ?", String.class,
                o.orderId())).as("a paid order keeps no failure").isNull();
    }

    @Test
    void theSameDeliveryTenTimesAtOnceIsProcessedOnce() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String body = webhookBody("payment.captured", o.orderId(), "pay_DUP000000001", o.amountPaise());
        String eventId = newEventId();
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            statuses.add(pool.submit(() -> {
                go.await();
                return send(body, eventId);
            }));
        }
        go.countDown();
        for (Future<Integer> s : statuses) {
            assertThat(s.get()).isEqualTo(200);
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(outcome(eventId)).isEqualTo("CONFIRMED");
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
    }

    @Test
    void aDeliveryWeFailedIsProcessedWhenRazorpayRetries() throws Exception {
        Order o = fx.razorpayOrder(fx.newUserToken("alice"));
        String body = webhookBody("payment.captured", o.orderId(), "pay_RETRY0000001", o.amountPaise());
        String eventId = newEventId();
        long seat = fx.seatIds().getLast();
        // Break an invariant so confirming throws (seat no longer HELD) → 500 and the whole transaction rolls back
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ?", fx.showId(), seat);

        assertThat(send(body, eventId)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id = ?", Integer.class, eventId))
                .as("the claim rolled back with the processing").isZero();
        assertThat(fx.paymentStatus(o.orderId())).as("capture rolled back too").isEqualTo("CREATED");

        jdbc.update("UPDATE show_seats SET status = 'HELD' WHERE show_id = ? AND seat_id = ?", fx.showId(), seat);
        assertThat(send(body, eventId)).as("Razorpay's retry, same event id").isEqualTo(200);

        assertThat(outcome(eventId)).isEqualTo("CONFIRMED");
        assertThat(fx.bookingStatus(o.bookingId())).isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------ helpers

    private String deliver(String body, int expectedStatus) throws Exception {
        String id = newEventId();
        assertThat(send(body, id)).isEqualTo(expectedStatus);
        return id;
    }

    private int send(String body, String eventId) throws Exception {
        return mvc.perform(post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Signature", webhookSignature(body))
                        .header("X-Razorpay-Event-Id", eventId))
                .andReturn().getResponse().getStatus();
    }

    private String newEventId() {
        String id = "evt_sc_" + UUID.randomUUID();
        eventIds.add(id);
        return id;
    }

    private String outcome(String eventId) {
        return jdbc.queryForObject("SELECT outcome FROM webhook_events WHERE event_id = ?", String.class, eventId);
    }
}
