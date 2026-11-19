package com.seatlock.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import testsupport.IntegrationTest;

/** The double-sold count runs against the real schema (index, partial predicate) and feeds the gauge. */
@IntegrationTest
class SeatInvariantMonitorIntegrationTest {

    @Autowired SeatInvariantMonitor monitor;
    @Autowired MeterRegistry meters;

    @Test
    void countsNoDoubleSoldSeatsAndPublishesTheGauge() {
        assertThat(monitor.check()).isZero();
        assertThat(meters.get(SeatInvariantMonitor.METER).gauge().value()).isZero();
    }

    @Test
    void holdAndOversellMetersAreRegistered() {
        // Registered eagerly so dashboards show 0 instead of "no data" before the first event.
        assertThat(meters.find("seatlock.oversell.blocked").counter()).isNotNull();
        assertThat(meters.find("seatlock.websocket.sessions").gauge()).isNotNull();
    }
}
