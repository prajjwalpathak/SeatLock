package com.seatlock.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.common.cache.CacheNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/**
 * What is cached and what must never be: proved by changing the DB behind the cache's back.
 * Every change is restored, because the Testcontainers DB is shared by all integration tests.
 */
@IntegrationTest
class CatalogCachingIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager caches;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry meters;

    long eventId;
    long showId;

    @BeforeEach
    void pickAnUpcomingShowAndStartCold() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT e.id AS event_id, s.id AS show_id FROM events e JOIN shows s ON s.event_id = e.id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now()
                ORDER BY s.starts_at LIMIT 1""");
        eventId = ((Number) row.get("event_id")).longValue();
        showId = ((Number) row.get("show_id")).longValue();
        caches.getCacheNames().forEach(name -> caches.getCache(name).invalidate()); // immediate; clear() may be deferred
    }

    @Test
    void eventDetailIsServedFromCacheUntilEvicted() throws Exception {
        String original = jdbc.queryForObject("SELECT title FROM events WHERE id = ?", String.class, eventId);
        mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(jsonPath("$.title").value(original)); // miss → cached
        try {
            jdbc.update("UPDATE events SET title = 'Changed Behind The Cache' WHERE id = ?", eventId);

            mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(jsonPath("$.title").value(original)); // hit
            caches.getCache(CacheNames.EVENT_DETAIL).evictIfPresent(eventId);
            mvc.perform(get("/api/v1/events/{id}", eventId))
                    .andExpect(jsonPath("$.title").value("Changed Behind The Cache"));            // fresh again
        } finally {
            jdbc.update("UPDATE events SET title = ? WHERE id = ?", original, eventId);
            caches.getCache(CacheNames.EVENT_DETAIL).evictIfPresent(eventId);
        }
    }

    /** The whole point of the cache: a hit must not open a transaction or borrow a DB connection. */
    @Test
    void eventDetailCacheHitBorrowsNoDatabaseConnection() throws Exception {
        mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(status().isOk()); // miss: uses the DB, then cached
        long borrowedBefore = connectionsBorrowed();

        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(status().isOk()); // hits
        }

        assertThat(connectionsBorrowed()).isEqualTo(borrowedBefore);
    }

    @Test
    void seatMapIsNeverCachedSoStatusIsAlwaysFresh() throws Exception {
        long seatId = jdbc.queryForObject(
                "SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE' ORDER BY seat_id LIMIT 1",
                Long.class, showId);
        String seatPath = "$.seats[?(@.seatId == " + seatId + ")].status";
        mvc.perform(get("/api/v1/shows/{id}/seats", showId)).andExpect(jsonPath(seatPath).value("AVAILABLE"));
        assertThat(redis.keys("seatlock:cache:*")).isEmpty(); // caches were cleared before; the seat map adds nothing
        try {
            jdbc.update("UPDATE show_seats SET status = 'BOOKED' WHERE show_id = ? AND seat_id = ?", showId, seatId);

            mvc.perform(get("/api/v1/shows/{id}/seats", showId)).andExpect(jsonPath(seatPath).value("BOOKED"));
        } finally {
            jdbc.update("UPDATE show_seats SET status = 'AVAILABLE' WHERE show_id = ? AND seat_id = ?", showId, seatId);
        }
    }

    @Test
    void cancelledShowIs404Immediately() throws Exception {
        mvc.perform(get("/api/v1/shows/{id}/seats", showId)).andExpect(status().isOk());
        try {
            jdbc.update("UPDATE shows SET status = 'CANCELLED' WHERE id = ?", showId);

            mvc.perform(get("/api/v1/shows/{id}/seats", showId)).andExpect(status().isNotFound());
        } finally {
            jdbc.update("UPDATE shows SET status = 'SCHEDULED' WHERE id = ?", showId);
        }
    }

    @Test
    void listPagesAreCachedPerCriteria() throws Exception {
        mvc.perform(get("/api/v1/events").param("city", "Mumbai").param("limit", "5")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/events").param("city", "mumbai").param("limit", "5")).andExpect(status().isOk());

        // Same criteria (city compared case-insensitively) → one cached page
        assertThat(redis.keys("seatlock:cache:eventSearch::*")).hasSize(1);
    }

    @Test
    void corruptCacheEntryIsTreatedAsAMissNotAnError() throws Exception {
        redis.opsForValue().set("seatlock:cache:eventDetail::" + eventId, "{not valid json");

        mvc.perform(get("/api/v1/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId));
    }

    /** HikariCP records one "usage" sample each time a connection is returned to the pool. */
    private long connectionsBorrowed() {
        return meters.get("hikaricp.connections.usage").timer().count();
    }

}
