package testsupport;

import com.seatlock.booking.hold.HoldKeys;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only view of the hold store behind the active SeatLockingStrategy, so race tests can assert "the store holds
 * exactly what the API said" for both strategies: Redis keys (redis-hold) or seat_hold_claims (optimistic).
 */
public final class HoldInspector {

    private final String strategy;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public HoldInspector(String strategy, StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.strategy = strategy;
        this.redis = redis;
        this.jdbc = jdbc;
    }

    public String strategy() {
        return strategy;
    }

    /** holdId holding the seat right now, or null. */
    public String holderOf(long showId, long seatId) {
        if (strategy.equals("optimistic")) {
            return jdbc.queryForList("""
                    SELECT hold_id::text FROM seat_hold_claims WHERE show_id = ? AND seat_id = ? AND expires_at > now()""",
                    String.class, showId, seatId).stream().findFirst().orElse(null);
        }
        return redis.opsForValue().get(HoldKeys.seatKey(showId, seatId));
    }

    /** Number of live hold records. */
    public int liveHolds() {
        if (strategy.equals("optimistic")) {
            return jdbc.queryForObject("SELECT count(*) FROM seat_holds WHERE expires_at > now()", Integer.class);
        }
        Set<String> keys = redis.keys("hold:id:*"); // test-only
        return keys == null ? 0 : keys.size();
    }

    /** Wipes every hold in both stores (test cleanup). */
    public void clear() {
        Set<String> keys = redis.keys("hold:*"); // test-only; production code never uses KEYS
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        jdbc.update("DELETE FROM seat_holds");
    }
}
