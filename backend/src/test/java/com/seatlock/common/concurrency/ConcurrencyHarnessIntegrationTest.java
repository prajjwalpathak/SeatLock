package com.seatlock.common.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import testsupport.ConcurrencyHarness;
import testsupport.RealServerTest;

/** The harness itself: 200 requests really overlap on a real port, and every outcome is collected. */
@RealServerTest
class ConcurrencyHarnessIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyHarnessIntegrationTest.class);
    private static final int N = 200;

    @Autowired Environment env;

    @Test
    void twoHundredRequestsOverlapAndAllComeBack() throws Exception {
        ConcurrencyHarness harness = new ConcurrencyHarness(env.getRequiredProperty("local.server.port", Integer.class));

        ConcurrencyHarness.Result result = harness.race(N, i -> harness.request("/api/v1/system/time").GET().build());
        log.info("harness smoke: {}", result.summary());

        assertThat(result.outcomes()).hasSize(N).allSatisfy(o -> assertThat(o.status()).isEqualTo(200));
        assertThat(result.peakInFlight())
                .as("requests must overlap, not run one after another")
                .isGreaterThanOrEqualTo(N / 10); // warm, fast endpoint: early requests finish while late threads wake
    }
}
