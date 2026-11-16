package com.seatlock.booking.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.booking.hold.HoldKeys;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import testsupport.ConcurrencyHarness.Outcome;
import testsupport.ConcurrencyHarness.Result;
import testsupport.RealServerTest;
import testsupport.TestUsers;

/**
 * The headline race, end to end: 200 different users press "Hold" on the same seat at the same instant,
 * over real HTTP (Tomcat, JWT filter, rate limiter, idempotency, DB checks, Lua). Exactly one may win.
 */
@RealServerTest
class HoldRace200IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HoldRace200IntegrationTest.class);
    private static final int USERS = 200;

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository userRepository;
    @Autowired TokenService tokenService;

    /** Once per JVM: the first race on a cold server (JIT, pools, Lua load) is not the one we measure. */
    private static boolean warmedUp;

    private ConcurrencyHarness harness;
    private List<String> tokens;
    private long showId;
    private long seatId;

    @BeforeEach
    void setUp() throws InterruptedException {
        harness = new ConcurrencyHarness(env.getRequiredProperty("local.server.port", Integer.class));
        tokens = new TestUsers(userRepository, tokenService).tokens("race-a", USERS);
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id LIMIT 1
                """, Long.class);
        seatId = jdbc.queryForObject("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 1
                """, Long.class, showId);
        if (!warmedUp) {
            warmUp();
            warmedUp = true;
        }
    }

    /** One unasserted race over the same path, on a different seat, then wipe its holds. */
    private void warmUp() throws InterruptedException {
        long spareSeat = jdbc.queryForObject("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' AND seat_id <> ?
                ORDER BY seat_id LIMIT 1
                """, Long.class, showId, seatId);
        List<String> warmUsers = new TestUsers(userRepository, tokenService).tokens("warm-up", USERS);
        Result warm = harness.race(USERS, i -> holdRequest(warmUsers.get(i), spareSeat));
        log.info("race A warm-up (not asserted): {}", warm.summary());
        removeHoldKeys();
    }

    private HttpRequest holdRequest(String token, long seat) {
        return harness.request("/api/v1/shows/" + showId + "/holds")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"seatIds\":[" + seat + "]}"))
                .build();
    }

    @AfterEach
    void removeHoldKeys() {
        Set<String> keys = redis.keys("hold:*"); // test-only; production code never uses KEYS
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @RepeatedTest(3)
    void twoHundredUsersOneSeatExactlyOneWinner() throws Exception {
        Result result = harness.race(USERS, i -> holdRequest(tokens.get(i), seatId));
        log.info("race A (200 users, 1 seat): {}", result.summary());

        assertThat(result.transportErrors()).as("every request must get an HTTP response").isEmpty();
        assertThat(result.statusCounts()).isEqualTo(Map.of(201, 1L, 409, (long) USERS - 1));
        assertThat(result.codeCounts()).isEqualTo(Map.of("SEATS_UNAVAILABLE", (long) USERS - 1));
        assertThat(result.withStatus(409)).allSatisfy(o ->
                assertThat(o.<List<Integer>>json("$.seatIds")).containsExactly((int) seatId));

        Outcome winner = result.withStatus(201).getFirst();
        String holdId = winner.json("$.holdId");
        assertThat(redis.opsForValue().get(HoldKeys.seatKey(showId, seatId)))
                .as("Redis must point at the one hold the API said was created")
                .isEqualTo(holdId);
        assertThat(redis.keys("hold:id:*")).as("no second hold record anywhere").hasSize(1);
        // Proves the requests contended (a serial loop would show 1). Not tighter: on a warm server the early
        // requests finish while the last threads are still waking, so the peak varies run to run (53-200 seen).
        assertThat(result.peakInFlight()).as("the requests really overlapped").isGreaterThanOrEqualTo(USERS / 10);
    }
}
