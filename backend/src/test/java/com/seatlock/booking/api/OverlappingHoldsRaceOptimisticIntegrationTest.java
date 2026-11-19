package com.seatlock.booking.api;

import org.springframework.test.context.TestPropertySource;

/** Race B (overlapping multi-seat holds, then every winner books) with the OPTIMISTIC strategy. */
@TestPropertySource(properties = "seatlock.holds.strategy=optimistic")
class OverlappingHoldsRaceOptimisticIntegrationTest extends OverlappingHoldsRaceIntegrationTest {
}
