package com.seatlock.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.seatlock.catalog.api.CreateShowRequest;
import com.seatlock.common.cache.CacheNames;
import com.seatlock.common.exception.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

/** Creating a show evicts exactly what it made stale, and only once the change is committed. */
@IntegrationTest
class CacheEvictionIntegrationTest {

    static final Instant BASE = Instant.now().plus(Duration.ofDays(200)).truncatedTo(ChronoUnit.DAYS);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager caches;
    @Autowired StringRedisTemplate redis;
    @Autowired OrganizerCatalogService organizerService;

    long organizerId;
    long eventId;
    long venueId;
    Map<String, Long> prices = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        organizerId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'organizer.cinema@seatlock.test'", Long.class);
        eventId = jdbc.queryForObject(
                "SELECT id FROM events WHERE organizer_id = ? AND status = 'PUBLISHED' ORDER BY id DESC LIMIT 1",
                Long.class, organizerId);
        venueId = jdbc.queryForObject("SELECT id FROM venues ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.queryForList("SELECT DISTINCT section FROM seats WHERE venue_id = ?", String.class, venueId)
                .forEach(section -> prices.put(section, 80_000L));
        caches.getCacheNames().forEach(name -> caches.getCache(name).invalidate()); // immediate; clear() may be deferred
    }

    @Test
    void newShowAppearsImmediatelyInTheCachedEventDetailAndListPagesAreDropped() throws Exception {
        mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(status().isOk()); // now cached
        mvc.perform(get("/api/v1/events")).andExpect(status().isOk());              // a cached list page
        assertThat(redis.keys("seatlock:cache:eventSearch::*")).isNotEmpty();

        long showId = organizerService.createShow(organizerId, eventId,
                new CreateShowRequest(venueId, BASE.plus(Duration.ofDays(6)).plus(Duration.ofHours(9)), prices)).showId();

        String body = mvc.perform(get("/api/v1/events/{id}", eventId)).andReturn().getResponse().getContentAsString();
        List<Number> showIds = JsonPath.read(body, "$.upcomingShows[*].id");
        assertThat(showIds).extracting(Number::longValue).contains(showId);
        assertThat(redis.keys("seatlock:cache:eventSearch::*")).isEmpty();
    }

    @Test
    void aRejectedShowEvictsNothing() throws Exception {
        Instant slot = BASE.plus(Duration.ofDays(7)).plus(Duration.ofHours(9));
        organizerService.createShow(organizerId, eventId, new CreateShowRequest(venueId, slot, prices));
        mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(status().isOk()); // cached again after that commit

        assertThatThrownBy(() -> organizerService.createShow(organizerId, eventId,
                new CreateShowRequest(venueId, slot.plus(Duration.ofMinutes(15)), prices)))
                .isInstanceOf(ConflictException.class);

        // rolled back → no AFTER_COMMIT event → the cached entry is untouched
        assertThat(redis.hasKey("seatlock:cache:" + CacheNames.EVENT_DETAIL + "::" + eventId)).isTrue();
    }
}
