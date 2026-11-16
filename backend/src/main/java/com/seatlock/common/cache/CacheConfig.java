package com.seatlock.common.cache;

import com.seatlock.catalog.api.EventDetailResponse;
import com.seatlock.catalog.api.EventSummaryResponse;
import com.seatlock.common.web.CursorPage;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis-backed Spring Cache (cache-aside). Keys look like {@code seatlock:cache:eventDetail::42}.
 * Each cache has its own TTL and its own typed JSON serializer. Caching is an optimization:
 * if Redis fails, the error is logged and the request falls through to the database.
 * <p>The cache advice runs BEFORE the transaction advice ({@code order = HIGHEST_PRECEDENCE}),
 * so a cache hit never opens a DB transaction or borrows a connection; only misses do.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CacheConfig implements CachingConfigurer {

    static final String KEY_PREFIX = "seatlock:cache:";
    static final String ENABLED_PROPERTY = "seatlock.cache.enabled";

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Name → (TTL, stored type). Short TTLs where data changes; long where it's static. */
    record CacheSpec(Duration ttl, JavaType type) {}

    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
    CacheManager cacheManager(RedisConnectionFactory connectionFactory, JsonMapper jsonMapper) {
        // SCAN in batches for "clear the whole cache" (KEYS would block Redis on a big keyspace).
        // immediateWrites: Spring Data Redis 4 writes asynchronously by default (put/evict/clear return before
        // Redis has the change). We need writes visible at once: an after-commit eviction must be done before
        // the next read, and tests read TTLs right after a put (CI/local flakes on W2·D6 and W3·D2).
        RedisCacheWriter writer = RedisCacheWriter.create(connectionFactory, config -> config // non-locking unless enableLocking()
                .batchStrategy(BatchStrategies.scan(1000))
                .immediateWrites());

        Map<String, RedisCacheConfiguration> caches = new LinkedHashMap<>();
        specs(jsonMapper).forEach((name, spec) -> caches.put(name, configFor(spec, jsonMapper)));

        return RedisCacheManager.builder(writer)
                .withInitialCacheConfigurations(caches)
                .disableCreateOnMissingCache() // a typo'd cache name fails fast instead of caching untyped data
                .enableStatistics()            // hits/misses/puts → Micrometer "cache.*" metrics
                .build();
    }

    static Map<String, CacheSpec> specs(JsonMapper json) {
        var types = json.getTypeFactory();
        Map<String, CacheSpec> specs = new LinkedHashMap<>();
        specs.put(CacheNames.EVENT_DETAIL,
                new CacheSpec(Duration.ofMinutes(5), types.constructType(EventDetailResponse.class)));
        specs.put(CacheNames.EVENT_SEARCH,
                new CacheSpec(Duration.ofSeconds(60),
                        types.constructParametricType(CursorPage.class, EventSummaryResponse.class)));
        return specs;
    }

    private static RedisCacheConfiguration configFor(CacheSpec spec, JsonMapper json) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(KEY_PREFIX)
                .entryTtl(spec.ttl())
                .disableCachingNullValues()
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(SerializationPair.fromSerializer(new TypedJsonRedisSerializer<>(json, spec.type())));
    }

    /**
     * SEATLOCK_CACHE_ENABLED=false: every @Cacheable runs its method (used to measure the
     * "before" numbers, and as a kill switch if the cache ever misbehaves in prod).
     */
    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "false")
    CacheManager noOpCacheManager() {
        log.warn("Caching is DISABLED ({}=false): every read goes to the database", ENABLED_PROPERTY);
        return new NoOpCacheManager();
    }

    /** Redis down or a bad entry → log and act as a miss, never fail the request. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
