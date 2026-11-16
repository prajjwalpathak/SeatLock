package com.seatlock.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HoldKeysTest {

    @Test
    void seatKeyFollowsTheSchema() {
        assertThat(HoldKeys.seatKey(42, 7)).isEqualTo("hold:show:42:seat:7");
        assertThat(HoldKeys.seatKey(42, 7)).startsWith(HoldKeys.PREFIX);
    }

    @Test
    void keysOfDifferentShowsNeverCollide() {
        // "show 1, seat 23" vs "show 12, seat 3": the separators keep them apart
        assertThat(HoldKeys.seatKey(1, 23)).isNotEqualTo(HoldKeys.seatKey(12, 3));
    }

    @Test
    void userAndRecordKeys() {
        assertThat(HoldKeys.userShowKey(9, 42)).isEqualTo("hold:user:9:show:42");
        assertThat(HoldKeys.recordKey("abc")).isEqualTo("hold:id:abc");
    }
}
