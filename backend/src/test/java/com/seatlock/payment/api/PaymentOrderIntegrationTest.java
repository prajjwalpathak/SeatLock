package com.seatlock.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

/** POST /api/v1/bookings/{publicId}/payment-order over the real stack with the mock gateway. */
@IntegrationTest
class PaymentOrderIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;

    private final List<Long> userIds = new ArrayList<>();
    private String alice;
    private String bob;
    private long showId;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        alice = token(newUser("alice"));
        bob = token(newUser("bob"));
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id LIMIT 1
                """, Long.class);
        seats = jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 2
                """, Long.class, showId);
    }

    @AfterEach
    void cleanUp() {
        String ids = userIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        String mine = "SELECT id FROM bookings WHERE user_id IN (%s)".formatted(ids);
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE booking_id IN (" + mine + "))");
        jdbc.update("DELETE FROM payments WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM booking_seats WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM bookings WHERE user_id IN (%s)".formatted(ids));
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ANY (?)",
                showId, seats.toArray(Long[]::new));
        Set<String> keys = redis.keys("hold:*"); // test-only
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void createsTheOrderWithTheServerSideAmountAndMovesTheBookingToPaymentPending() throws Exception {
        String booking = book(alice, seats.get(0), seats.get(1));
        String publicId = JsonPath.read(booking, "$.publicId");
        long total = ((Number) JsonPath.read(booking, "$.totalPaise")).longValue();
        String expiresAt = JsonPath.read(booking, "$.expiresAt");

        String body = payOrder(alice, publicId)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.bookingId").value(publicId))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.gateway").value("MOCK"))
                .andExpect(jsonPath("$.amountPaise").value(total))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.payBy").value(expiresAt))
                .andExpect(jsonPath("$.keyId").value(nullValue())) // mock gateway: no browser checkout
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.orderId")).matches("order_mock_[A-Za-z0-9]{14}");

        mvc.perform(get("/api/v1/bookings/{id}", publicId).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
        assertThat(jdbc.queryForObject("""
                SELECT p.amount_paise FROM payments p JOIN bookings b ON b.id = p.booking_id WHERE b.public_id = ?::uuid
                """, Long.class, publicId)).isEqualTo(total);
    }

    @Test
    void repeatingTheCallReturnsTheSameOpenOrder() throws Exception {
        String publicId = JsonPath.read(book(alice, seats.get(0)), "$.publicId");

        String first = payOrder(alice, publicId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = payOrder(alice, publicId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.orderId")).isEqualTo(JsonPath.read(first, "$.orderId"));
        assertThat(paymentCount(publicId)).isEqualTo(1);
    }

    @Test
    void aNewAttemptIsAllowedAfterAFailedOne() throws Exception {
        String publicId = JsonPath.read(book(alice, seats.get(0)), "$.publicId");
        String first = payOrder(alice, publicId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE payments SET status = 'FAILED', failed_at = now() WHERE gateway_order_id = ?",
                (String) JsonPath.read(first, "$.orderId"));

        String second = payOrder(alice, publicId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.orderId")).isNotEqualTo(JsonPath.read(first, "$.orderId"));
        assertThat(paymentCount(publicId)).isEqualTo(2);
    }

    @Test
    void cancelledOrPaidBookingsCannotBePaid() throws Exception {
        String cancelled = JsonPath.read(book(alice, seats.get(0)), "$.publicId");
        mvc.perform(post("/api/v1/bookings/{id}/cancel", cancelled).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk());
        String paid = JsonPath.read(book(bob, seats.get(1)), "$.publicId");
        jdbc.update("UPDATE bookings SET status = 'CONFIRMED', confirmed_at = now() WHERE public_id = ?::uuid", paid);

        payOrder(alice, cancelled)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_BOOKING_TRANSITION"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.action").value("startPayment"));
        payOrder(bob, paid)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(paymentCount(cancelled) + paymentCount(paid)).isZero();
    }

    @Test
    void expiredBookingsCannotBePaidEvenBeforeTheSweeperRuns() throws Exception {
        String swept = JsonPath.read(book(alice, seats.get(0)), "$.publicId");
        jdbc.update("UPDATE bookings SET status = 'EXPIRED' WHERE public_id = ?::uuid", swept);
        String overdue = JsonPath.read(book(bob, seats.get(1)), "$.publicId");
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 second' WHERE public_id = ?::uuid", overdue);

        payOrder(alice, swept).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));
        payOrder(bob, overdue).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE public_id = ?::uuid", String.class, overdue))
                .isEqualTo("HELD");
        assertThat(paymentCount(swept) + paymentCount(overdue)).isZero();
    }

    @Test
    void someoneElsesOrAnUnknownBookingIs404() throws Exception {
        String publicId = JsonPath.read(book(alice, seats.get(0)), "$.publicId");

        payOrder(bob, publicId).andExpect(status().isNotFound());
        payOrder(alice, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        payOrder(alice, "not-a-uuid").andExpect(status().isNotFound());
        assertThat(paymentCount(publicId)).isZero();
    }

    @Test
    void aTamperedTotalIsNeverCharged() throws Exception {
        String publicId = JsonPath.read(book(alice, seats.get(0)), "$.publicId");
        jdbc.update("UPDATE bookings SET total_paise = 1 WHERE public_id = ?::uuid", publicId);

        payOrder(alice, publicId).andExpect(status().isInternalServerError());
        assertThat(paymentCount(publicId)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private int paymentCount(String publicId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM payments p JOIN bookings b ON b.id = p.booking_id WHERE b.public_id = ?::uuid
                """, Integer.class, publicId);
    }

    private ResultActions payOrder(String token, String publicId) throws Exception {
        return mvc.perform(post("/api/v1/bookings/{id}/payment-order", publicId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    /** hold → booking; returns the booking JSON. */
    private String book(String token, Long... seatIds) throws Exception {
        String hold = mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":" + List.of(seatIds) + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mvc.perform(post("/api/v1/holds/{id}/booking", (String) JsonPath.read(hold, "$.holdId"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private User newUser(String name) {
        User user = users.save(new User(name + "-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}unused", name, Role.USER, AuthProvider.LOCAL));
        userIds.add(user.getId());
        return user;
    }

    private String token(User user) {
        return tokenService.issueAccessToken(
                new AuthenticatedUser(user.getId(), user.getEmail(), Role.USER, "unused")).value();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
