package com.seatlock.realtime;

import com.seatlock.catalog.domain.ShowSeatStatus;
import com.seatlock.catalog.events.SeatsChangedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Both ends of the cross-instance relay (W5·D2). Every instance publishes its seat changes to one Redis
 * Pub/Sub channel and forwards everything it receives on that channel to its own STOMP broker, including
 * its own events: one delivery path, so local and remote viewers see the same messages in the same order.
 * <p>Wire format {@code "<version>|<json>"}: the version is assigned inside the publishing Lua script.</p>
 * <p>Pub/Sub is at-most-once (nothing is kept for an instance that is disconnected); a lost message shows up
 * as a version gap and the client resyncs.</p>
 */
@Component
class SeatEventRelay {

    static final String CHANNEL = "realtime:seat-events";

    private static final RedisScript<Long> PUBLISH =
            RedisScript.of(new ClassPathResource("redis/publish_seat_event.lua"), Long.class);
    /** Own mapper (not the app's): the format is shared only by our two ends, and must not change with web config. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The event as relayed; the version travels in front of it. */
    record Relayed(long showId, ShowSeatStatus status, List<Long> seatIds, Instant holdExpiresAt, Instant occurredAt) {
    }

    private final StringRedisTemplate redis;

    SeatEventRelay(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Numbers and publishes the event; returns its version. */
    long publish(SeatsChangedEvent event, Instant occurredAt) {
        String json = JSON.writeValueAsString(new Relayed(event.showId(), event.status(), event.seatIds(),
                event.holdExpiresAt(), occurredAt));
        Long version = redis.execute(PUBLISH, List.of(SeatEventVersions.key(event.showId())), CHANNEL, json);
        if (version == null) {
            throw new IllegalStateException("publish_seat_event.lua returned no version");
        }
        return version;
    }

    /** Parses a received relay message. */
    static SeatEventMessage decode(byte[] body) {
        String raw = new String(body, StandardCharsets.UTF_8);
        int bar = raw.indexOf('|');
        if (bar <= 0) {
            throw new IllegalArgumentException("Not a seat event: no version prefix");
        }
        long version = Long.parseLong(raw.substring(0, bar));
        Relayed r = JSON.readValue(raw.substring(bar + 1), Relayed.class);
        return new SeatEventMessage(r.showId(), version, r.status(), r.seatIds(), r.holdExpiresAt(), r.occurredAt());
    }
}
