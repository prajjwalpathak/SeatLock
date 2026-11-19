package com.seatlock.payment.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.AuthenticatedUser;
import com.seatlock.identity.security.TokenService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real hold → booking → payment order through the API (mock gateway), then the payment row is re-labelled as a
 * Razorpay order so the Razorpay callback / webhook paths can be exercised without calling Razorpay.
 * Signatures use the test profile's secrets. Call {@link #cleanUp()} after each test.
 */
public final class PaymentFixtures {

    public static final String KEY_SECRET = "test-key-secret";       // application-test.yml
    public static final String WEBHOOK_SECRET = "test-webhook-secret";

    public record Order(String token, String bookingId, String orderId, long amountPaise) {}

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final UserRepository users;
    private final TokenService tokens;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> seatIds = new ArrayList<>();
    private final long showId;

    public PaymentFixtures(MockMvc mvc, JdbcTemplate jdbc, StringRedisTemplate redis, UserRepository users,
                           TokenService tokens) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.redis = redis;
        this.users = users;
        this.tokens = tokens;
        this.showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '3 days'
                ORDER BY s.id LIMIT 1
                """, Long.class);
    }

    public long showId() {
        return showId;
    }

    /** Registers a seat a test took itself, so {@link #cleanUp()} frees it too. */
    public void track(long seatId) {
        seatIds.add(seatId);
    }

    /** Seats handed out by this fixture so far (for assertions on show_seats). */
    public List<Long> seatIds() {
        return List.copyOf(seatIds);
    }

    public String newUserToken(String name) {
        User user = users.save(new User(name + "-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}unused", name, Role.USER, AuthProvider.LOCAL));
        userIds.add(user.getId());
        return tokens.issueAccessToken(new AuthenticatedUser(user.getId(), user.getEmail(), Role.USER, "unused")).value();
    }

    /** A PAYMENT_PENDING booking for one free seat with an open order labelled as Razorpay's. */
    public Order razorpayOrder(String token) throws Exception {
        Order mock = mockOrder(token);
        String razorpayOrderId = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        jdbc.update("UPDATE payments SET gateway = 'RAZORPAY', gateway_order_id = ? WHERE gateway_order_id = ?",
                razorpayOrderId, mock.orderId());
        return new Order(token, mock.bookingId(), razorpayOrderId, mock.amountPaise());
    }

    /** A PAYMENT_PENDING booking for one free seat with an open order from the (active) mock gateway. */
    public Order mockOrder(String token) throws Exception {
        long seat = jdbc.queryForObject("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 1
                """, Long.class, showId);
        seatIds.add(seat);
        String hold = mvc.perform(post("/api/v1/shows/{id}/holds", showId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seatIds\":[" + seat + "]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String booking = mvc.perform(post("/api/v1/holds/{id}/booking", (String) JsonPath.read(hold, "$.holdId"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String bookingId = JsonPath.read(booking, "$.publicId");
        String order = mvc.perform(post("/api/v1/bookings/{id}/payment-order", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Order(token, bookingId, JsonPath.read(order, "$.orderId"),
                ((Number) JsonPath.read(order, "$.amountPaise")).longValue());
    }

    public static String checkoutSignature(String orderId, String paymentId) {
        return hmac(KEY_SECRET, (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    public static String webhookSignature(String body) {
        return hmac(WEBHOOK_SECRET, body.getBytes(StandardCharsets.UTF_8));
    }

    /** A Razorpay-shaped webhook body (only the fields we read, plus some we don't). */
    public static String webhookBody(String event, String orderId, String paymentId, long amountPaise) {
        return """
                {"entity":"event","account_id":"acc_TEST","event":"%s","contains":["payment"],
                 "payload":{"payment":{"entity":{"id":"%s","entity":"payment","amount":%d,"currency":"INR",
                 "status":"captured","order_id":"%s","method":"netbanking","captured":true}}},
                 "created_at":1764583200}""".formatted(event, paymentId, amountPaise, orderId);
    }

    public String bookingStatus(String bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE public_id = ?::uuid", String.class, bookingId);
    }

    public String paymentStatus(String orderId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE gateway_order_id = ?", String.class, orderId);
    }

    public void cleanUp() {
        String ids = userIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        String mine = "SELECT id FROM bookings WHERE user_id IN (%s)".formatted(ids);
        jdbc.update("DELETE FROM refunds WHERE payment_id IN (SELECT id FROM payments WHERE booking_id IN (" + mine + "))");
        jdbc.update("DELETE FROM payments WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM booking_seats WHERE booking_id IN (" + mine + ")");
        jdbc.update("DELETE FROM bookings WHERE user_id IN (%s)".formatted(ids));
        if (!seatIds.isEmpty()) {
            jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ANY (?)",
                    showId, seatIds.toArray(Long[]::new));
        }
        Set<String> keys = redis.keys("hold:*"); // test-only
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static String hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
