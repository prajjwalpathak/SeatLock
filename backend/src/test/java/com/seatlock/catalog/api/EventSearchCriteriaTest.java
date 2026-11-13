package com.seatlock.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EventSearchCriteriaTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void defaultsLimitAndTurnsBlanksIntoNull() {
        EventSearchCriteria c = new EventSearchCriteria(" ", "  ", null, "", null, null, " ", null);
        assertThat(c.limit()).isEqualTo(EventSearchCriteria.DEFAULT_LIMIT);
        assertThat(c.q()).isNull();
        assertThat(c.city()).isNull();
        assertThat(c.language()).isNull();
        assertThat(c.cursor()).isNull();
    }

    @Test
    void acceptsValidCriteria() {
        EventSearchCriteria c = new EventSearchCriteria("comedy night", "Mumbai", null, "Hindi",
                Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-11-27T00:00:00Z"), null, 10);
        assertThat(validator.validate(c)).isEmpty();
    }

    @Test
    void rejectsLimitAboveMaximum() {
        EventSearchCriteria c = new EventSearchCriteria(null, null, null, null, null, null, null, 500);
        assertThat(validator.validate(c))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("limit");
    }

    @Test
    void rejectsOverlongSearchText() {
        EventSearchCriteria c = new EventSearchCriteria("x".repeat(101), null, null, null, null, null, null, null);
        assertThat(validator.validate(c))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("q");
    }

    @Test
    void rejectsInvertedTimeRange() {
        EventSearchCriteria c = new EventSearchCriteria(null, null, null, null,
                Instant.parse("2026-11-27T00:00:00Z"), Instant.parse("2026-11-20T00:00:00Z"), null, null);
        assertThat(validator.validate(c))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("timeRangeValid");
    }
}