package com.seatlock.common.cache;

/** Every cache in the app, so names can't drift between @Cacheable, @CacheEvict and config. */
public final class CacheNames {

    /** Published event + upcoming shows, by event id. */
    public static final String EVENT_DETAIL = "eventDetail";
    /** One page of the public event list, by normalized search criteria + cursor. */
    public static final String EVENT_SEARCH = "eventSearch";

    private CacheNames() {
    }
}
