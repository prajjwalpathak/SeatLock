package com.seatlock.booking.api;

import org.springframework.test.context.TestPropertySource;

/**
 * Race A (200 users, 1 seat over real HTTP) with the OPTIMISTIC strategy: holds as conditional upserts in Postgres,
 * no Redis on the hold path. Same assertions, read from seat_hold_claims instead of Redis keys.
 */
@TestPropertySource(properties = "seatlock.holds.strategy=optimistic")
class HoldRace200OptimisticIntegrationTest extends HoldRace200IntegrationTest {
}
