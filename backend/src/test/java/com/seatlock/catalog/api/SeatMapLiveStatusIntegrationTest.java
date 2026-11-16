package com.seatlock.catalog.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatlock.booking.hold.HoldKeys;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import testsupport.IntegrationTest;

/** Seat map over HTTP with real Postgres + real Redis hold keys. */
@IntegrationTest
class SeatMapLiveStatusIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    private long showId;
    private List<Long> seatIds;

    @BeforeEach
    void pickShowAndSeats() {
        showId = jdbc.queryForObject("""
                SELECT s.id FROM shows s JOIN events e ON e.id = s.event_id
                WHERE e.status = 'PUBLISHED' AND s.status = 'SCHEDULED' ORDER BY s.id LIMIT 1
                """, Long.class);
        seatIds = jdbc.queryForList("""
                SELECT seat_id FROM show_seats WHERE show_id = ? AND status = 'AVAILABLE'
                ORDER BY seat_id LIMIT 2
                """, Long.class, showId);
    }

    @AfterEach
    void removeHoldKeys() {
        seatIds.forEach(id -> redis.delete(HoldKeys.seatKey(showId, id)));
    }

    @Test
    void aSeatWithAHoldKeyIsHeldAndTheResponseIsNeverCached() throws Exception {
        long held = seatIds.get(0);
        long free = seatIds.get(1);
        redis.opsForValue().set(HoldKeys.seatKey(showId, held), "hold-1", Duration.ofMinutes(1));

        mvc.perform(get("/api/v1/shows/{id}/seats", showId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.holdsLive").value(true))
                .andExpect(jsonPath("$.seats[?(@.seatId == %d)].status".formatted(held)).value("HELD"))
                .andExpect(jsonPath("$.seats[?(@.seatId == %d)].status".formatted(free)).value("AVAILABLE"));
    }

    @Test
    @Transactional // the BOOKED update is rolled back after the test
    void bookedInPostgresWinsOverAHoldKey() throws Exception {
        long seat = seatIds.get(0);
        jdbc.update("UPDATE show_seats SET status = 'BOOKED' WHERE show_id = ? AND seat_id = ?", showId, seat);
        redis.opsForValue().set(HoldKeys.seatKey(showId, seat), "stale-hold", Duration.ofMinutes(1));

        mvc.perform(get("/api/v1/shows/{id}/seats", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[?(@.seatId == %d)].status".formatted(seat)).value("BOOKED"));
    }
}
