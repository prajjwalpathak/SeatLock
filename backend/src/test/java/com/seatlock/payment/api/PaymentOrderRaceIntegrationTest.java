package com.seatlock.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * 20 "Pay now" requests for one booking at the same instant (double-clicks, retries, two tabs), over real HTTP.
 * Exactly one order may be created; everyone else gets that same order back.
 */
@RealServerTest
class PaymentOrderRaceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderRaceIntegrationTest.class);
    private static final int REQUESTS = 20;

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository userRepository;
    @Autowired TokenService tokenService;

    private final HttpClient http = HttpClient.newHttpClient();
    private ConcurrencyHarness harness;
    private String token;
    private long showId;
    private long seatId;
    private String bookingId;

    @BeforeEach
    void setUp() throws Exception {
        harness = new ConcurrencyHarness(env.getRequiredProperty("local.server.port", Integer.class));
        token = new TestUsers(userRepository, tokenService).tokens("pay-race", 1).getFirst();
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id DESC LIMIT 1
                """, Long.class);
        seatId = jdbc.queryForObject("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 1
                """, Long.class, showId);

        String hold = send(harness.request("/api/v1/shows/" + showId + "/holds")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"seatIds\":[" + seatId + "]}")).build(), 201);
        String booking = send(harness.request("/api/v1/holds/" + JsonPath.read(hold, "$.holdId") + "/booking")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), 201);
        bookingId = JsonPath.read(booking, "$.publicId");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE booking_id = (SELECT id FROM bookings WHERE public_id = ?::uuid))", bookingId);
        jdbc.update("DELETE FROM payments WHERE booking_id = (SELECT id FROM bookings WHERE public_id = ?::uuid)", bookingId);
        jdbc.update("DELETE FROM booking_seats WHERE booking_id = (SELECT id FROM bookings WHERE public_id = ?::uuid)", bookingId);
        jdbc.update("DELETE FROM bookings WHERE public_id = ?::uuid", bookingId);
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ?", showId, seatId);
        Set<String> keys = redis.keys("hold:*"); // test-only
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @RepeatedTest(5)
    void twentyConcurrentPayNowRequestsCreateExactlyOneOrder() throws Exception {
        Result result = harness.race(REQUESTS, i -> harness.request("/api/v1/bookings/" + bookingId + "/payment-order")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        log.info("payment-order race ({} requests, 1 booking): {}", REQUESTS, result.summary());

        assertThat(result.transportErrors()).as("every request must get an HTTP response").isEmpty();
        assertThat(result.statusCounts()).isEqualTo(Map.of(201, 1L, 200, (long) REQUESTS - 1));

        Set<String> orderIds = result.outcomes().stream()
                .map(o -> o.<String>json("$.orderId"))
                .collect(Collectors.toSet());
        assertThat(orderIds).as("everyone got the same order").hasSize(1);

        assertThat(jdbc.queryForList("""
                SELECT p.gateway_order_id FROM payments p JOIN bookings b ON b.id = p.booking_id
                WHERE b.public_id = ?::uuid
                """, String.class, bookingId))
                .as("one payment row, and it is the order the API returned")
                .containsExactlyElementsOf(orderIds);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE public_id = ?::uuid", String.class, bookingId))
                .isEqualTo("PAYMENT_PENDING");
    }

    private String send(HttpRequest request, int expectedStatus) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return response.body();
    }
}
