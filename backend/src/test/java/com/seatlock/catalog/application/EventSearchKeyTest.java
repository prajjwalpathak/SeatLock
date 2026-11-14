package com.seatlock.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.catalog.api.EventSearchCriteria;
import com.seatlock.catalog.domain.EventCategory;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventSearchKeyTest {

    @Test
    void equivalentCriteriaShareOneKey() {
        var a = new EventSearchCriteria("  kites ", "Mumbai", EventCategory.CONCERT, "HINDI", null, null, null, null);
        var b = new EventSearchCriteria("kites", "mumbai", EventCategory.CONCERT, "hindi", null, null, "", 20);

        assertThat(EventCatalogService.searchKey(a, true)).isEqualTo(EventCatalogService.searchKey(b, true));
    }

    @Test
    void aStartsAfterInThePastIsTheSameAsNoneBecauseBothMeanFromNow() {
        var past = new EventSearchCriteria(null, null, null, null, Instant.parse("2020-01-01T00:00:00Z"), null, null, null);
        var none = new EventSearchCriteria(null, null, null, null, null, null, null, null);

        assertThat(EventCatalogService.searchKey(past, true)).isEqualTo(EventCatalogService.searchKey(none, true));
    }

    @Test
    void separatorsInsideValuesCantForgeAnotherFieldsKey() {
        var crafted = new EventSearchCriteria("a|city=x", null, null, null, null, null, null, null);
        var real = new EventSearchCriteria("a", "x", null, null, null, null, null, null);

        assertThat(EventCatalogService.searchKey(crafted, true)).isNotEqualTo(EventCatalogService.searchKey(real, true));
    }

    @Test
    void differentPagesAndLimitsAreDifferentKeys() {
        var first = new EventSearchCriteria(null, null, null, null, null, null, null, 10);
        var next = new EventSearchCriteria(null, null, null, null, null, null, "abc", 10);
        var bigger = new EventSearchCriteria(null, null, null, null, null, null, null, 20);

        assertThat(EventCatalogService.searchKey(first, true))
                .isNotEqualTo(EventCatalogService.searchKey(next, true))
                .isNotEqualTo(EventCatalogService.searchKey(bigger, true));
    }
}
