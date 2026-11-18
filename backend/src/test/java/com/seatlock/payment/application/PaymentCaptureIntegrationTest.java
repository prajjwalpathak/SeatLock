package com.seatlock.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.booking.application.BookingExpiryService;
import com.seatlock.common.exception.NotFoundException;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import com.seatlock.payment.application.PaymentCaptureService.Outcome;
import com.seatlock.payment.gateway.GatewayName;
import java.time.Instant;
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
import testsupport.IntegrationTest;

/** Capture → confirm against the real DB: payment CAPTURED, booking CONFIRMED, seats BOOKED, all or nothing. */
@IntegrationTest
class PaymentCaptureIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired TokenService tokenService;
    @Autowired PaymentCaptureService capture;
    @Autowired BookingExpiryService expiry;

    private final List<Long> userIds = new ArrayList<>();
    private String alice;
    private long showId;
    private List<Long> seats;

    @BeforeEach
    void setUp() {
        alice = token(newUser("alice"));
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
    void captureConfirmsTheBookingAndBooksTheSeats() throws Exception {
        Order o = orderFor(seats.get(0), seats.get(1));

        var result = capture.capture(GatewayName.MOCK, o.orderId, "pay_ONE", o.amount);

        assertThat(result.outcome()).isEqualTo(Outcome.CONFIRMED);
        assertThat(result.bookingId().toString()).isEqualTo(o.bookingId);
        assertThat(bookingStatus(o)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL FROM bookings WHERE public_id = ?::uuid",
                Boolean.class, o.bookingId)).isTrue();
        assertThat(jdbc.queryForList("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ANY (?)",
                String.class, showId, seats.toArray(Long[]::new))).containsOnly("BOOKED");
        assertThat(jdbc.queryForMap("SELECT status, gateway_payment_id FROM payments WHERE gateway_order_id = ?", o.orderId))
                .containsEntry("status", "CAPTURED").containsEntry("gateway_payment_id", "pay_ONE");
    }

    @Test
    void theSamePaymentTwiceIsANoOp() throws Exception {
        Order o = orderFor(seats.get(0));
        capture.capture(GatewayName.MOCK, o.orderId, "pay_ONE", o.amount);

        assertThat(capture.capture(GatewayName.MOCK, o.orderId, "pay_ONE", null).outcome()).isEqualTo(Outcome.ALREADY_DONE);
        assertThat(capture.capture(GatewayName.MOCK, o.orderId, "pay_TWO", o.amount).outcome())
                .as("a second payment for the same order needs a refund").isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(jdbc.queryForObject("SELECT gateway_payment_id FROM payments WHERE gateway_order_id = ?",
                String.class, o.orderId)).isEqualTo("pay_ONE");
    }

    @Test
    void aWrongAmountNeverConfirms() throws Exception {
        Order o = orderFor(seats.get(0));

        assertThat(capture.capture(GatewayName.MOCK, o.orderId, "pay_ONE", o.amount - 1).outcome())
                .isEqualTo(Outcome.NEEDS_REVIEW);
        assertThat(bookingStatus(o)).isEqualTo("PAYMENT_PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE gateway_order_id = ?", String.class, o.orderId))
                .isEqualTo("CREATED");
    }

    @Test
    void aLatePaymentStillConfirmsIfTheSweeperHasNotRun() throws Exception {
        Order o = orderFor(seats.get(0));
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '5 seconds' WHERE public_id = ?::uuid", o.bookingId);

        assertThat(capture.capture(GatewayName.MOCK, o.orderId, "pay_ONE", o.amount).outcome()).isEqualTo(Outcome.CONFIRMED);
        assertThat(bookingStatus(o)).isEqualTo("CONFIRMED");
    }

    @Test
    void aPaymentAfterExpiryIsCapturedButTheBookingStaysLost() throws Exception {
        Order o = orderFor(seats.get(0));
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '5 seconds' WHERE public_id = ?::uuid", o.bookingId);
        expiry.expireBatch(Instant.now(), 100);

        assertThat(capture.capture(GatewayName.MOCK, o.orderId, "pay_ONE", o.amount).outcome())
                .isEqualTo(Outcome.CAPTURED_BOOKING_LOST);
        assertThat(bookingStatus(o)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE gateway_order_id = ?", String.class, o.orderId))
                .as("money was taken: keep the record for the refund (W4·D4)").isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?",
                String.class, showId, seats.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void anUnknownOrderIsNotFound() {
        assertThatThrownBy(() -> capture.capture(GatewayName.MOCK, "order_nope", "pay_ONE", 100L))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers

    record Order(String bookingId, String orderId, long amount) {}

    private String bookingStatus(Order o) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE public_id = ?::uuid", String.class, o.bookingId);
    }

    private Order orderFor(Long... seatIds) throws Exception {
        String hold = mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":" + List.of(seatIds) + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String booking = mvc.perform(post("/api/v1/holds/{id}/booking", (String) JsonPath.read(hold, "$.holdId"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String bookingId = JsonPath.read(booking, "$.publicId");
        String order = mvc.perform(post("/api/v1/bookings/{id}/payment-order", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Order(bookingId, JsonPath.read(order, "$.orderId"),
                ((Number) JsonPath.read(order, "$.amountPaise")).longValue());
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
}
