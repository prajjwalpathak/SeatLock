package com.seatlock.booking.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.booking.hold.HoldKeys;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
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
 * Race B: 200 users grab random, overlapping 1-4 seat sets inside one 12-seat block at the same instant,
 * then every winner converts their hold into a booking at the same instant. Whatever the interleaving,
 * no seat may end up in two holds or two live bookings, and Redis, show_seats and booking_seats must agree.
 * The seed is logged, so a failing interleaving's requests can be replayed exactly.
 */
@RealServerTest
class OverlappingHoldsRaceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OverlappingHoldsRaceIntegrationTest.class);
    private static final int USERS = 200;
    private static final int BLOCK = 12;
    private static final int MAX_SEATS_PER_USER = 4;
    private static final String EMAIL_PREFIX = "race-b";

    /** Any seat with more than one live claim. THE invariant; must always be empty. */
    static final String DOUBLE_SOLD_SEATS = """
            SELECT show_id, seat_id, count(*) AS live_claims FROM booking_seats
            WHERE active GROUP BY show_id, seat_id HAVING count(*) > 1
            """;

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository userRepository;
    @Autowired TokenService tokenService;

    private ConcurrencyHarness harness;
    private long showId;
    private List<Long> block;

    @BeforeEach
    void setUp() {
        harness = new ConcurrencyHarness(env.getRequiredProperty("local.server.port", Integer.class));
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id LIMIT 1
                """, Long.class);
        block = jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT ?
                """, Long.class, showId, BLOCK);
        assertThat(block).hasSize(BLOCK);
    }

    @AfterEach
    void cleanUp() {
        String users = "SELECT id FROM users WHERE email LIKE '" + EMAIL_PREFIX + "-%'";
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ANY (?)",
                showId, block.toArray(Long[]::new));
        jdbc.update("DELETE FROM booking_seats WHERE booking_id IN (SELECT id FROM bookings WHERE user_id IN (" + users + "))");
        jdbc.update("DELETE FROM bookings WHERE user_id IN (" + users + ")");
        Set<String> keys = redis.keys("hold:*"); // test-only; production code never uses KEYS
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @RepeatedTest(3)
    void overlappingHoldsThenBookingsNeverSellASeatTwice(RepetitionInfo repetition) throws Exception {
        long seed = System.nanoTime() + repetition.getCurrentRepetition();
        Random random = new Random(seed);
        List<List<Long>> wanted = new ArrayList<>(USERS);
        for (int i = 0; i < USERS; i++) {
            List<Long> shuffled = new ArrayList<>(block);
            Collections.shuffle(shuffled, random);
            wanted.add(List.copyOf(shuffled.subList(0, 1 + random.nextInt(MAX_SEATS_PER_USER))));
        }
        List<String> tokens = new TestUsers(userRepository, tokenService).tokens(EMAIL_PREFIX, USERS);

        // ---- phase 1: everyone holds at once
        Result holds = harness.race(USERS, i -> harness.request("/api/v1/shows/" + showId + "/holds")
                .header("Authorization", "Bearer " + tokens.get(i))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"seatIds\":" + wanted.get(i) + "}"))
                .build());
        log.info("race B holds (seed={}): {}", seed, holds.summary());

        assertThat(holds.transportErrors()).as("every request must get an HTTP response").isEmpty();
        assertThat(holds.statusCounts().keySet()).as("only 201 or 409, never a 5xx").isSubsetOf(201, 409);
        assertThat(holds.codeCounts().keySet()).isSubsetOf(Set.of("SEATS_UNAVAILABLE"));

        List<Outcome> winners = holds.withStatus(201);
        assertThat(winners).as("someone always wins the first seat").isNotEmpty();
        Map<Long, String> heldBy = new HashMap<>(); // seat → holdId
        for (Outcome w : winners) {
            String holdId = w.json("$.holdId");
            for (Long seat : wanted.get(w.index())) {
                assertThat(heldBy.put(seat, holdId)).as("seat %d is in two holds (seed=%d)", seat, seed).isNull();
            }
        }
        for (Long seat : block) { // Redis says exactly what the API said, nothing more
            assertThat(redis.opsForValue().get(HoldKeys.seatKey(showId, seat))).as("seat %d", seat)
                    .isEqualTo(heldBy.get(seat));
        }
        assertThat(redis.keys("hold:id:*")).hasSize(winners.size());
        for (Outcome loser : holds.withStatus(409)) { // a loser's reported conflicts really are held by someone
            assertThat(loser.<List<Integer>>json("$.seatIds")).isNotEmpty()
                    .allSatisfy(s -> assertThat(heldBy).containsKey(s.longValue()));
        }

        // ---- phase 2: every winner proceeds to pay at once
        Result bookings = harness.race(winners.size(), i -> harness
                .request("/api/v1/holds/" + winners.get(i).<String>json("$.holdId") + "/booking")
                .header("Authorization", "Bearer " + tokens.get(winners.get(i).index()))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        log.info("race B bookings: {}", bookings.summary());

        assertThat(bookings.transportErrors()).isEmpty();
        assertThat(bookings.statusCounts()).as("disjoint holds all convert").isEqualTo(Map.of(201, (long) winners.size()));

        // ---- the database is the final word
        assertThat(jdbc.queryForList(DOUBLE_SOLD_SEATS)).as("double-sold seats (seed=%d)", seed).isEmpty();
        Set<Long> liveClaims = new HashSet<>(jdbc.queryForList("""
                SELECT seat_id FROM booking_seats WHERE active AND show_id = ? AND seat_id = ANY (?)
                """, Long.class, showId, block.toArray(Long[]::new)));
        Set<Long> heldInShowSeats = new HashSet<>(jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE status = 'HELD' AND show_id = ? AND seat_id = ANY (?)
                """, Long.class, showId, block.toArray(Long[]::new)));
        assertThat(liveClaims).as("booked = exactly the seats the winners held").isEqualTo(heldBy.keySet());
        assertThat(heldInShowSeats).as("show_seats agrees with booking_seats").isEqualTo(liveClaims);

        Map<Long, Long> bookingsPerUser = jdbc.queryForList("""
                SELECT b.user_id, count(*) AS n FROM bookings b JOIN users u ON u.id = b.user_id
                WHERE u.email LIKE ? GROUP BY b.user_id
                """, EMAIL_PREFIX + "-%").stream()
                .collect(Collectors.toMap(r -> ((Number) r.get("user_id")).longValue(), r -> ((Number) r.get("n")).longValue()));
        assertThat(bookingsPerUser).as("only winners booked, once each").hasSize(winners.size())
                .allSatisfy((user, n) -> assertThat(n).isEqualTo(1L));
        log.info("race B result (seed={}): {} winners, {} of {} seats sold, 0 double-sold",
                seed, winners.size(), liveClaims.size(), BLOCK);
    }
}
