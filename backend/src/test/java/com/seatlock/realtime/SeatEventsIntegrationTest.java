package com.seatlock.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.catalog.application.ShowSeatStatusService;
import com.seatlock.catalog.domain.ShowSeatStatus;
import com.seatlock.identity.domain.UserRepository;
import com.seatlock.identity.security.TokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import testsupport.BrokerSubscriptions;
import testsupport.RealServerTest;
import testsupport.StompTestClient;
import testsupport.TestUsers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Observer end to end: a change (hold over real HTTP, or a committed DB update) → domain event →
 * AFTER_COMMIT broadcaster → Redis relay → in-memory broker → a real STOMP client on {@code /ws}.
 */
@RealServerTest
class SeatEventsIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired TokenService tokenService;
    @Autowired ShowSeatStatusService seatStatus;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationContext app;

    private int port;
    private StompTestClient stomp;
    private long showId;
    private long otherShowId;
    private long seatId;

    @BeforeEach
    void setUp() throws Exception {
        port = env.getRequiredProperty("local.server.port", Integer.class);
        // Second/third bookable show and the last free seat: away from the seats the races use
        List<Long> shows = jdbc.queryForList("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id OFFSET 1 LIMIT 2
                """, Long.class);
        showId = shows.get(0);
        otherShowId = shows.get(1);
        seatId = jdbc.queryForObject("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id DESC LIMIT 1
                """, Long.class, showId);
        stomp = new StompTestClient(port).connected();
    }

    @AfterEach
    void tearDown() {
        stomp.close();
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ?", showId, seatId);
    }

    @Test
    void holdAndReleaseOverHttpAreBroadcastWithConsecutiveVersions() throws Exception {
        BlockingQueue<SeatEventMessage> events = subscribed(SeatTopics.forShow(showId));
        String token = new TestUsers(userRepository, tokenService).tokens("ws-hold", 1).getFirst();
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> created = http.send(HttpRequest.newBuilder(uri("/api/v1/shows/" + showId + "/holds"))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"seatIds\":[" + seatId + "]}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        String holdId = JSON.readTree(created.body()).get("holdId").asString();

        SeatEventMessage held = next(events);
        assertThat(held.showId()).isEqualTo(showId);
        assertThat(held.status()).isEqualTo(ShowSeatStatus.HELD);
        assertThat(held.seatIds()).containsExactly(seatId);
        assertThat(held.holdExpiresAt()).as("a hold expires by itself; clients need the instant").isNotNull();

        HttpResponse<String> released = http.send(HttpRequest.newBuilder(uri("/api/v1/holds/" + holdId))
                .header("Authorization", "Bearer " + token).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(released.statusCode()).isEqualTo(204);

        SeatEventMessage freed = next(events);
        assertThat(freed.status()).isEqualTo(ShowSeatStatus.AVAILABLE);
        assertThat(freed.seatIds()).containsExactly(seatId);
        assertThat(freed.holdExpiresAt()).isNull();
        assertThat(freed.version()).as("per-show sequence, no gaps").isEqualTo(held.version() + 1);
    }

    @Test
    void databaseChangesAreBroadcastOnlyAfterCommitAndOnlyToThatShow() throws Exception {
        BlockingQueue<SeatEventMessage> events = subscribed(SeatTopics.forShow(showId));
        BlockingQueue<SeatEventMessage> otherShow = subscribed(SeatTopics.forShow(otherShowId));

        tx.executeWithoutResult(status -> {
            assertThat(seatStatus.markHeld(showId, List.of(seatId))).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(events.poll(1, TimeUnit.SECONDS)).as("a rolled-back change is never announced").isNull();

        tx.executeWithoutResult(status -> seatStatus.markHeld(showId, List.of(seatId)));
        SeatEventMessage held = next(events);
        assertThat(held.status()).isEqualTo(ShowSeatStatus.HELD);
        assertThat(held.holdExpiresAt()).as("a booking's HELD has no TTL").isNull();

        tx.executeWithoutResult(status -> seatStatus.markAvailable(showId, List.of(seatId)));
        assertThat(next(events).version()).isEqualTo(held.version() + 1);

        tx.executeWithoutResult(status ->
                assertThat(seatStatus.markAvailable(showId, List.of(seatId))).as("already AVAILABLE").isZero());
        assertThat(events.poll(500, TimeUnit.MILLISECONDS)).as("no change, no event").isNull();
        assertThat(otherShow).as("other shows' viewers hear nothing").isEmpty();
    }

    @Test
    void theSeatMapCarriesTheLastVersionSoAClientCanResyncWithoutLosingUpdates() throws Exception {
        BlockingQueue<SeatEventMessage> events = subscribed(SeatTopics.forShow(showId));
        tx.executeWithoutResult(status -> seatStatus.markHeld(showId, List.of(seatId)));
        SeatEventMessage held = next(events);

        // Resync = re-fetch the seat map: its version covers the event, and the seat shows the new status
        JsonNode map = seatMap();
        assertThat(map.get("version").asLong()).isEqualTo(held.version());
        assertThat(statusOf(map, seatId)).isEqualTo("HELD");

        tx.executeWithoutResult(status -> seatStatus.markAvailable(showId, List.of(seatId)));
        SeatEventMessage freed = next(events);
        assertThat(freed.version()).as("the next change is newer than the map: the client applies it")
                .isGreaterThan(map.get("version").asLong());
        assertThat(seatMap().get("version").asLong()).isEqualTo(freed.version());
    }

    // ------------------------------------------------------------------ helpers

    private BlockingQueue<SeatEventMessage> subscribed(String destination) throws InterruptedException {
        return BrokerSubscriptions.subscribe(app, stomp, destination);
    }

    private static SeatEventMessage next(BlockingQueue<SeatEventMessage> events) throws InterruptedException {
        SeatEventMessage message = events.poll(StompTestClient.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(message).as("seat event within %s", StompTestClient.TIMEOUT).isNotNull();
        return message;
    }

    private JsonNode seatMap() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri("/api/v1/shows/" + showId + "/seats")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static String statusOf(JsonNode map, long seat) {
        for (JsonNode s : map.get("seats")) {
            if (s.get("seatId").asLong() == seat) {
                return s.get("status").asString();
            }
        }
        throw new AssertionError("seat " + seat + " not in the map");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
