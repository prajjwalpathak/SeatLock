package com.seatlock.payment.api;

import static com.seatlock.payment.support.PaymentFixtures.checkoutSignature;
import static com.seatlock.payment.support.PaymentFixtures.webhookBody;
import static com.seatlock.payment.support.PaymentFixtures.webhookSignature;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.booking.application.BookingExpiryService;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import testsupport.ConcurrencyHarness;
import testsupport.ConcurrencyHarness.Result;
import testsupport.RealServerTest;
import testsupport.TestUsers;

/**
 * One payment, every path at once, over real HTTP: the browser's checkout callback (retried by an impatient user)
 * and Razorpay's webhook (redelivered), optionally while the expiry sweeper runs. Exactly one of them may confirm,
 * and the booking, its seats and the payment must always agree.
 */
@RealServerTest
class PaymentConfirmRaceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmRaceIntegrationTest.class);
    private static final int CALLBACKS = 10;
    private static final int WEBHOOKS = 10;

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository userRepository;
    @Autowired TokenService tokenService;
    @Autowired BookingExpiryService expiry;

    private final HttpClient http = HttpClient.newHttpClient();
    private ConcurrencyHarness harness;
    private String token;
    private long showId;
    private long seatId;
    private String bookingId;
    private String orderId;
    private long amount;
    private final String paymentId = "pay_RACE" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    @BeforeEach
    void setUp() throws Exception {
        harness = new ConcurrencyHarness(env.getRequiredProperty("local.server.port", Integer.class));
        token = new TestUsers(userRepository, tokenService).tokens("confirm-race", 1).getFirst();
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id DESC LIMIT 1 OFFSET 1
                """, Long.class);
        seatId = jdbc.queryForObject("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 1
                """, Long.class, showId);

        String hold = send(authed("/api/v1/shows/" + showId + "/holds")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"seatIds\":[" + seatId + "]}")).build());
        bookingId = JsonPath.read(send(authed("/api/v1/holds/" + JsonPath.read(hold, "$.holdId") + "/booking")
                .POST(HttpRequest.BodyPublishers.noBody()).build()), "$.publicId");
        String order = send(authed("/api/v1/bookings/" + bookingId + "/payment-order")
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        amount = ((Number) JsonPath.read(order, "$.amountPaise")).longValue();
        orderId = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        jdbc.update("UPDATE payments SET gateway = 'RAZORPAY', gateway_order_id = ? WHERE gateway_order_id = ?",
                orderId, (String) JsonPath.read(order, "$.orderId"));
    }

    @AfterEach
    void cleanUp() {
        String booking = "(SELECT id FROM bookings WHERE public_id = '" + bookingId + "'::uuid)";
        jdbc.update("DELETE FROM webhook_events WHERE event_id LIKE 'evt_race_%'");
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE booking_id = " + booking + ")");
        jdbc.update("DELETE FROM payments WHERE booking_id = " + booking);
        jdbc.update("DELETE FROM booking_seats WHERE booking_id = " + booking);
        jdbc.update("DELETE FROM bookings WHERE public_id = ?::uuid", bookingId);
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ?", showId, seatId);
        Set<String> keys = redis.keys("hold:*"); // test-only
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @RepeatedTest(5)
    void callbacksAndWebhooksAtOnceConfirmExactlyOnce() throws Exception {
        Result result = harness.race(CALLBACKS + WEBHOOKS, this::request);
        log.info("confirm race ({} callbacks + {} webhooks): {}", CALLBACKS, WEBHOOKS, result.summary());

        assertThat(result.transportErrors()).isEmpty();
        assertThat(result.statusCounts()).isEqualTo(Map.of(200, (long) CALLBACKS + WEBHOOKS));
        List<String> outcomes = allOutcomes(result);
        assertThat(outcomes.stream().filter("CONFIRMED"::equals).count()).as("exactly one confirms").isEqualTo(1);
        assertThat(outcomes).containsOnly("CONFIRMED", "ALREADY_DONE");
        assertConsistent("CONFIRMED", "BOOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id LIKE 'evt_race_%'",
                Integer.class)).as("every distinct delivery recorded once").isEqualTo(WEBHOOKS);
    }

    @RepeatedTest(5)
    void aPaymentRacingTheSweeperEitherConfirmsOrIsLostNeverHalfway() throws Exception {
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 second' WHERE public_id = ?::uuid", bookingId);
        AtomicBoolean racing = new AtomicBoolean(true);
        AtomicInteger expired = new AtomicInteger();
        CompletableFuture<Void> sweeper = CompletableFuture.runAsync(() -> {
            while (racing.get()) {
                expired.addAndGet(expiry.expireBatch(Instant.now(), 100));
            }
        });

        Result result = harness.race(CALLBACKS + WEBHOOKS, this::request);
        racing.set(false);
        sweeper.get();
        log.info("confirm vs sweeper: {} (sweeper expired {})", result.summary(), expired.get());

        assertThat(result.transportErrors()).isEmpty();
        assertThat(result.statusCounts()).isEqualTo(Map.of(200, (long) CALLBACKS + WEBHOOKS));
        String status = jdbc.queryForObject("SELECT status FROM bookings WHERE public_id = ?::uuid", String.class, bookingId);
        List<String> outcomes = allOutcomes(result);
        // (expired counts every booking the sweeper expired, including leftovers of other tests, so only >= 1.)
        if (status.equals("CONFIRMED")) {
            assertThat(outcomes.stream().filter("CONFIRMED"::equals).count()).isEqualTo(1);
            assertConsistent("CONFIRMED", "BOOKED");
        } else {
            assertThat(status).isEqualTo("EXPIRED");
            assertThat(expired.get()).isPositive();
            assertThat(outcomes).doesNotContain("CONFIRMED").contains("CAPTURED_BOOKING_LOST");
            assertConsistent("EXPIRED", "AVAILABLE");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Even indexes: checkout callback (same signed payload, as a user double-clicking). Odd: webhook redeliveries. */
    private HttpRequest request(int i) {
        if (i % 2 == 0) {
            return authed("/api/v1/payments/razorpay/verify")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"orderId":"%s","paymentId":"%s","signature":"%s"}"""
                            .formatted(orderId, paymentId, checkoutSignature(orderId, paymentId))))
                    .build();
        }
        String body = webhookBody(i % 4 == 1 ? "payment.captured" : "order.paid", orderId, paymentId, amount);
        return harness.request("/api/v1/webhooks/razorpay")
                .header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", webhookSignature(body))
                .header("X-Razorpay-Event-Id", "evt_race_" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /** Callback outcomes come from the response body, webhook outcomes from webhook_events. */
    private List<String> allOutcomes(Result result) {
        List<String> callbacks = result.outcomes().stream()
                .filter(o -> o.index() % 2 == 0)
                .map(o -> o.<String>json("$.outcome"))
                .toList();
        List<String> webhooks = jdbc.queryForList(
                "SELECT outcome FROM webhook_events WHERE event_id LIKE 'evt_race_%'", String.class);
        return java.util.stream.Stream.concat(callbacks.stream(), webhooks.stream()).collect(Collectors.toList());
    }

    private void assertConsistent(String bookingStatus, String seatStatus) {
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE public_id = ?::uuid", String.class, bookingId))
                .isEqualTo(bookingStatus);
        assertThat(jdbc.queryForObject("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?",
                String.class, showId, seatId)).as("seat status matches the booking").isEqualTo(seatStatus);
        assertThat(jdbc.queryForMap("SELECT status, gateway_payment_id FROM payments WHERE gateway_order_id = ?", orderId))
                .as("the money was taken exactly once, whatever happened to the booking")
                .containsEntry("status", "CAPTURED").containsEntry("gateway_payment_id", paymentId);
    }

    private HttpRequest.Builder authed(String path) {
        return harness.request(path).header("Authorization", "Bearer " + token);
    }

    private String send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 201);
        return response.body();
    }
}
