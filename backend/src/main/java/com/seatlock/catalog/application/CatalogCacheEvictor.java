package com.seatlock.catalog.application;

import com.seatlock.catalog.events.ShowScheduledEvent;
import com.seatlock.common.cache.CacheNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops cached catalog data that a change made stale, only AFTER the change is committed.
 * Evicting before the commit would let a concurrent reader re-cache the old data from the DB
 * in the gap; a rolled-back change evicts nothing.
 */
@Component
public class CatalogCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(CatalogCacheEvictor.class);

    private final CacheManager caches;

    public CatalogCacheEvictor(CacheManager caches) {
        this.caches = caches;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShowScheduled(ShowScheduledEvent event) {
        evict(CacheNames.EVENT_DETAIL, event.eventId()); // its upcoming-shows list changed
        clear(CacheNames.EVENT_SEARCH);                   // "next show" / "from price" may change on any page
    }

    private void evict(String cacheName, Object key) {
        try {
            // evictIfPresent, not evict: the Cache contract lets evict() run asynchronously (Spring Data
            // Redis 4 does), which would leave a window where readers still see the old entry.
            cache(cacheName).evictIfPresent(key);
        } catch (RuntimeException ex) {
            // Redis down: the entry still expires by its TTL (5 min), so staleness is bounded
            log.warn("Could not evict {}::{} ({})", cacheName, key, ex.getMessage());
        }
    }

    private void clear(String cacheName) {
        try {
            cache(cacheName).invalidate(); // immediate (clear() may be deferred); SCAN-based, never KEYS
        } catch (RuntimeException ex) {
            log.warn("Could not clear {} ({})", cacheName, ex.getMessage());
        }
    }

    private Cache cache(String name) {
        Cache cache = caches.getCache(name);
        if (cache == null) {
            throw new IllegalStateException("Cache not configured: " + name);
        }
        return cache;
    }
}
