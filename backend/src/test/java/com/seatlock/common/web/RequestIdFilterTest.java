package com.seatlock.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestIdFilterTest {

    @Test
    void keepsSafeClientSuppliedId() {
        assertThat(RequestIdFilter.resolveRequestId("abc-123-XYZ")).isEqualTo("abc-123-XYZ");
    }

    @Test
    void generatesUuidWhenHeaderMissing() {
        String id = RequestIdFilter.resolveRequestId(null);
        assertThat(UUID.fromString(id)).isNotNull(); // throws if not a valid UUID
    }

    @Test
    void replacesUnsafeIdToPreventLogInjection() {
        String id = RequestIdFilter.resolveRequestId("evil\nINFO fake log line");
        assertThat(id).doesNotContain("\n");
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void replacesOverlongId() {
        String id = RequestIdFilter.resolveRequestId("a".repeat(65));
        assertThat(id).hasSize(36); // a generated UUID
    }
}