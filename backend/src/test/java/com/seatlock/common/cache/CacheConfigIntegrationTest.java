package com.seatlock.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.catalog.api.EventDetailResponse;
import com.seatlock.catalog.api.ShowSummaryResponse;
import com.seatlock.catalog.domain.EventCategory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import testsupport.IntegrationTest;

/** The cache infrastructure against real Redis: key format, TTL, typed JSON, metrics. */
@IntegrationTest
class CacheConfigIntegrationTest {

    @Autowired CacheManager cacheManager;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry meters;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void entriesRoundTripAsPlainJsonUnderOurPrefixWithTheirTtl() {
        Cache cache = cacheManager.getCache(CacheNames.EVENT_DETAIL);
        var detail = new EventDetailResponse(900_001L, "Cached Show", "desc", EventCategory.CONCERT, "Hindi", 120,
                List.of(new ShowSummaryResponse(1L, Instant.parse("2026-12-01T14:00:00Z"), 2L, "Hall", "Delhi", 50_000)));

        cache.put(900_001L, detail);

        String key = CacheConfig.KEY_PREFIX + CacheNames.EVENT_DETAIL + "::900001";
        assertThat(redis.getExpire(key)).isBetween(1L, 300L);                    // 5 min TTL
        assertThat(redis.opsForValue().get(key))
                .startsWith("{").contains("\"title\":\"Cached Show\"")
                .doesNotContain("@class");                                          // no type info in the data
        assertThat(cache.get(900_001L, EventDetailResponse.class)).isEqualTo(detail); // typed read back
    }

    @Test
    void unknownCacheNamesAreNotCreatedSilently() {
        assertThat(cacheManager.getCacheNames())
                .contains(CacheNames.EVENT_DETAIL, CacheNames.EVENT_SEARCH);
        assertThat(cacheManager.getCache("eventDetial")).isNull(); // typo → no untyped cache
    }

    /**
     * Checks the counts move, not just that the meters exist (Spring registers cache.gets even when
     * statistics are off; it would then read 0 forever). Goes through the real path, HTTP →
     * @Cacheable, because that's what production counts. On failure, the message shows Spring Data
     * Redis's own raw statistics next to the Micrometer meters, to tell a counting bug from extra traffic.
     */
    @Test
    void cacheHitsAndMissesAreCounted() throws Exception {
        long eventId = jdbc.queryForObject("""
                SELECT e.id FROM events e JOIN shows s ON s.event_id = e.id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' AND s.starts_at > now()
                ORDER BY e.id LIMIT 1""", Long.class);
        Cache cache = cacheManager.getCache(CacheNames.EVENT_DETAIL);
        cache.evictIfPresent(eventId);
        double hitsBefore = gets("hit");
        double missesBefore = gets("miss");
        Raw rawBefore = raw();

        mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(status().isOk()); // miss → loaded → cached
        mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(status().isOk()); // hit
        mvc.perform(get("/api/v1/events/{id}", eventId)).andExpect(status().isOk()); // hit

        Raw rawAfter = raw();
        String diagnosis = "meters: +%s misses, +%s hits | Spring Data Redis raw: +%d misses, +%d hits".formatted(
                gets("miss") - missesBefore, gets("hit") - hitsBefore,
                rawAfter.misses() - rawBefore.misses(),
                rawAfter.hits() - rawBefore.hits());
        assertThat(gets("miss") - missesBefore).as(diagnosis).isEqualTo(1);
        assertThat(gets("hit") - hitsBefore).as(diagnosis).isEqualTo(2);
    }

    private Raw raw() {
        CacheStatistics live = ((RedisCache) cacheManager.getCache(CacheNames.EVENT_DETAIL)).getStatistics();
        return new Raw(live.getHits(), live.getMisses()); // the two getters Spring Boot's own Redis cache metrics use
    }

    private double gets(String result) {
        return meters.get("cache.gets").tag("cache", CacheNames.EVENT_DETAIL).tag("result", result)
                .functionCounter().count();
    }

    /** Copy of the three numbers we compare (Spring Data Redis statistics are live views). */
    private record Raw(long hits, long misses) {}
}
