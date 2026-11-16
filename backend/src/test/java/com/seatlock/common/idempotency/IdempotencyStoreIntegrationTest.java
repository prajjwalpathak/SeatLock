package com.seatlock.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.common.idempotency.IdempotencyRecord.Status;
import com.seatlock.common.idempotency.IdempotencyStore.Claim;
import com.seatlock.identity.domain.AuthProvider;
import com.seatlock.identity.domain.Role;
import com.seatlock.identity.domain.User;
import com.seatlock.identity.domain.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import testsupport.IntegrationTest;

/** The idempotency table against real Postgres. Fresh users and random keys per test: no cleanup needed. */
@IntegrationTest
class IdempotencyStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z");
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String ENDPOINT = "POST /api/v1/shows/{showId}/holds";
    private static final String HASH = "a".repeat(64);

    @Autowired IdempotencyStore store;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private long alice;
    private long bob;
    private String key;

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        bob = newUser("bob");
        key = UUID.randomUUID().toString();
    }

    @Test
    void firstRequestClaimsTheKeyAndARetrySeesItInProgress() {
        Claim first = store.claim(alice, key, ENDPOINT, HASH, NOW, TTL);
        Claim retry = store.claim(alice, key, ENDPOINT, "b".repeat(64), NOW.plusSeconds(1), TTL);

        assertThat(first).isInstanceOf(Claim.Claimed.class);
        assertThat(retry).isInstanceOfSatisfying(Claim.Existing.class, e -> {
            assertThat(e.record().status()).isEqualTo(Status.IN_PROGRESS);
            assertThat(e.record().requestHash()).isEqualTo(HASH);          // the ORIGINAL request's hash
            assertThat(e.record().expiresAt()).isEqualTo(NOW.plus(TTL));
            assertThat(e.record().responseStatus()).isNull();
        });
    }

    @Test
    void keysAreScopedPerUser() {
        assertThat(store.claim(alice, key, ENDPOINT, HASH, NOW, TTL)).isInstanceOf(Claim.Claimed.class);
        assertThat(store.claim(bob, key, ENDPOINT, HASH, NOW, TTL)).isInstanceOf(Claim.Claimed.class);
    }

    @Test
    void aCompletedKeyReturnsTheStoredResponse() {
        long id = ((Claim.Claimed) store.claim(alice, key, ENDPOINT, HASH, NOW, TTL)).id();

        store.complete(id, 201, "{\"holdId\":\"h\"}", NOW.plusSeconds(1));

        assertThat(store.claim(alice, key, ENDPOINT, HASH, NOW.plusSeconds(2), TTL))
                .isInstanceOfSatisfying(Claim.Existing.class, e -> {
                    assertThat(e.record().status()).isEqualTo(Status.COMPLETED);
                    assertThat(e.record().responseStatus()).isEqualTo(201);
                    assertThat(e.record().responseBody()).isEqualTo("{\"holdId\":\"h\"}");
                });
    }

    @Test
    void aReleasedKeyCanBeClaimedAgain() {
        long id = ((Claim.Claimed) store.claim(alice, key, ENDPOINT, HASH, NOW, TTL)).id();

        store.release(id);

        assertThat(store.claim(alice, key, ENDPOINT, HASH, NOW, TTL)).isInstanceOf(Claim.Claimed.class);
    }

    @Test
    void releaseNeverDeletesACompletedKey() {
        long id = ((Claim.Claimed) store.claim(alice, key, ENDPOINT, HASH, NOW, TTL)).id();
        store.complete(id, 201, "{}", NOW);

        store.release(id);

        assertThat(store.find(alice, key)).map(IdempotencyRecord::status).contains(Status.COMPLETED);
    }

    @Test
    void aStaleInProgressClaimCanBeTakenOverOnceAfterTheLease() {
        long id = ((Claim.Claimed) store.claim(alice, key, ENDPOINT, HASH, NOW, TTL)).id();

        assertThat(store.takeOver(id, NOW.plusSeconds(10), LEASE)).isFalse();     // owner may still be working
        assertThat(store.takeOver(id, NOW.plusSeconds(31), LEASE)).isTrue();      // owner presumed dead
        assertThat(store.takeOver(id, NOW.plusSeconds(32), LEASE)).isFalse();     // the new owner is fresh now
        assertThat(store.find(alice, key).orElseThrow().lockedAt()).isEqualTo(NOW.plusSeconds(31));
    }

    @Test
    void aCompletedKeyIsNeverTakenOver() {
        long id = ((Claim.Claimed) store.claim(alice, key, ENDPOINT, HASH, NOW, TTL)).id();
        store.complete(id, 201, "{}", NOW);

        assertThat(store.takeOver(id, NOW.plus(Duration.ofHours(1)), LEASE)).isFalse();
    }

    @Test
    void cleanupDeletesOnlyExpiredKeys() {
        String old = UUID.randomUUID().toString();
        store.claim(alice, old, ENDPOINT, HASH, NOW.minus(Duration.ofHours(25)), TTL); // expired 1 h ago
        store.claim(alice, key, ENDPOINT, HASH, NOW, TTL);                              // fresh

        int deleted = store.deleteExpired(NOW);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(store.find(alice, old)).isEmpty();
        assertThat(store.find(alice, key)).isPresent();
    }

    @Test
    void twentyConcurrentClaimsOfOneKeyHaveExactlyOneWinner() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Claim>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return store.claim(alice, key, ENDPOINT, HASH, NOW, TTL);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Claim> claims = new ArrayList<>();
            for (Future<Claim> f : futures) {
                claims.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(claims).filteredOn(Claim.Claimed.class::isInstance).hasSize(1);
            assertThat(claims).filteredOn(Claim.Existing.class::isInstance).hasSize(threads - 1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?", Integer.class, alice, key))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private long newUser(String name) {
        return users.save(new User(name + "-" + UUID.randomUUID() + "@example.com",
                "{bcrypt}unused", name, Role.USER, AuthProvider.LOCAL)).getId();
    }
}
