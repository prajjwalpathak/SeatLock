package com.seatlock.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import tools.jackson.databind.json.JsonMapper;

/** SEATLOCK_CACHE_ENABLED switches between the Redis cache and no caching at all. */
class CacheKillSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CacheConfig.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

    @Test
    void redisCacheByDefault() {
        runner.run(ctx -> assertThat(ctx.getBean(CacheManager.class)).isInstanceOf(RedisCacheManager.class));
    }

    @Test
    void noCachingWhenDisabled() {
        runner.withPropertyValues("seatlock.cache.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(CacheManager.class)).isInstanceOf(NoOpCacheManager.class));
    }
}
