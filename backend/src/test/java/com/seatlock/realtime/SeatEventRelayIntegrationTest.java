package com.seatlock.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.SeatLockApplication;
import com.seatlock.catalog.application.ShowSeatStatusService;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import testsupport.BrokerSubscriptions;
import testsupport.RealServerTest;
import testsupport.StompTestClient;

/**
 * Two instances of the app on one Redis and one Postgres (instance A = the test context, instance B started
 * here, like the two containers behind Nginx on W5·D4). Changes made on both at the same time must reach the
 * viewers of both, in one order, with consecutive versions.
 */
@RealServerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeatEventRelayIntegrationTest {

    private static final int CHANGES_PER_INSTANCE = 20;

    @Autowired ApplicationContext appA;
    @Autowired JdbcTemplate jdbc;
    @Autowired LettuceConnectionFactory redis;
    @Autowired DataSource dataSource;

    private ConfigurableApplicationContext appB;
    private StompTestClient viewerA;
    private StompTestClient viewerB;
    private long showId;
    private List<Long> seats;

    @BeforeAll
    void startSecondInstance() {
        HikariDataSource db = (HikariDataSource) dataSource;
        // Command-line arguments, not builder .properties(): those are only defaults, and application-test.yml's
        // placeholder datasource (localhost:5432/unused) would win over them.
        appB = new SpringApplicationBuilder(SeatLockApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.data.redis.host=" + redis.getHostName(),
                        "--spring.data.redis.port=" + redis.getPort(),
                        "--spring.datasource.url=" + db.getJdbcUrl(),
                        "--spring.datasource.username=" + db.getUsername(),
                        "--spring.datasource.password=" + db.getPassword());
    }

    @AfterAll
    void stopSecondInstance() {
        if (appB != null) {
            appB.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now() + interval '1 hour'
                ORDER BY s.id OFFSET 3 LIMIT 1
                """, Long.class);
        seats = jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id DESC LIMIT 2
                """, Long.class, showId);
        viewerA = new StompTestClient(port(appA)).connected();
        viewerB = new StompTestClient(port(appB)).connected();
    }

    @AfterEach
    void tearDown() {
        viewerA.close();
        viewerB.close();
        jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id IN (?, ?)",
                showId, seats.get(0), seats.get(1));
    }

    @Test
    void concurrentChangesOnBothInstancesReachBothViewersInOneConsecutiveOrder() throws Exception {
        String topic = SeatTopics.forShow(showId);
        BlockingQueue<SeatEventMessage> onA = BrokerSubscriptions.subscribe(appA, viewerA, topic);
        BlockingQueue<SeatEventMessage> onB = BrokerSubscriptions.subscribe(appB, viewerB, topic);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService writers = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = writers.submit(() -> toggle(appA, seats.get(0), start));
            Future<?> b = writers.submit(() -> toggle(appB, seats.get(1), start));
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            writers.shutdownNow();
        }

        List<SeatEventMessage> seenOnA = drain(onA, 2 * CHANGES_PER_INSTANCE);
        List<SeatEventMessage> seenOnB = drain(onB, 2 * CHANGES_PER_INSTANCE);

        long first = seenOnA.getFirst().version();
        for (int i = 0; i < seenOnA.size(); i++) {
            assertThat(seenOnA.get(i).version()).as("consecutive versions, in order, on A").isEqualTo(first + i);
        }
        assertThat(seenOnB).as("instance B saw exactly what A saw, in the same order").isEqualTo(seenOnA);
        assertThat(seenOnA).extracting(SeatEventMessage::seatIds)
                .as("changes from both instances are in the stream")
                .contains(List.of(seats.get(0)), List.of(seats.get(1)));
    }

    /** HELD → AVAILABLE → HELD … on one seat, each change in its own committed transaction on that instance. */
    private void toggle(ApplicationContext app, long seatId, CountDownLatch start) {
        ShowSeatStatusService seatStatus = app.getBean(ShowSeatStatusService.class);
        TransactionTemplate tx = app.getBean(TransactionTemplate.class);
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int i = 0; i < CHANGES_PER_INSTANCE; i++) {
            boolean hold = i % 2 == 0;
            tx.executeWithoutResult(s -> {
                int moved = hold ? seatStatus.markHeld(showId, List.of(seatId)) : seatStatus.markAvailable(showId, List.of(seatId));
                assertThat(moved).isEqualTo(1);
            });
        }
    }

    private static List<SeatEventMessage> drain(BlockingQueue<SeatEventMessage> queue, int expected)
            throws InterruptedException {
        List<SeatEventMessage> all = new ArrayList<>();
        while (all.size() < expected) {
            SeatEventMessage m = queue.poll(StompTestClient.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(m).as("message %d of %d", all.size() + 1, expected).isNotNull();
            all.add(m);
        }
        assertThat(queue.poll(300, TimeUnit.MILLISECONDS)).as("no extra messages").isNull();
        return all;
    }

    private static int port(ApplicationContext app) {
        return app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }
}
