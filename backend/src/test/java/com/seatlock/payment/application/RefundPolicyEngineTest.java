package com.seatlock.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Default policy (application.yml): ≥ 48 h → 100 %, ≥ 24 h → 50 %, less → not cancellable. */
class RefundPolicyEngineTest {

    static final Instant SHOW = Instant.parse("2026-12-10T14:00:00Z");
    // given out of order on purpose: the engine sorts them
    final RefundPolicyEngine engine = new RefundPolicyEngine(List.of(
            new RefundProperties.Tier(24, 50), new RefundProperties.Tier(48, 100)));

    private RefundPolicyEngine.Quote at(Duration beforeShow, long paid) {
        return engine.quote(paid, SHOW, SHOW.minus(beforeShow));
    }

    @Test
    void fullRefundFromFortyEightHoursOut() {
        var q = at(Duration.ofDays(5), 150_000);
        assertThat(q.cancellable()).isTrue();
        assertThat(q.percent()).isEqualTo(100);
        assertThat(q.refundPaise()).isEqualTo(150_000);
        assertThat(q.nextChangeAt()).isEqualTo(SHOW.minus(Duration.ofHours(48)));
    }

    @Test
    void tierBoundariesAreInclusive() {
        assertThat(at(Duration.ofHours(48), 100).percent()).isEqualTo(100);
        assertThat(at(Duration.ofHours(48).minusSeconds(1), 100).percent()).isEqualTo(50);
        assertThat(at(Duration.ofHours(24), 100).percent()).isEqualTo(50);
        assertThat(at(Duration.ofHours(24).minusSeconds(1), 100).cancellable()).isFalse();
    }

    @Test
    void partialRefundsRoundDownToThePaisa() {
        assertThat(at(Duration.ofHours(30), 99_999).refundPaise()).isEqualTo(49_999);
        assertThat(at(Duration.ofHours(30), 1).refundPaise()).isZero();
    }

    @Test
    void notCancellableInTheLastDayOrAfterTheStart() {
        var lastDay = at(Duration.ofHours(3), 100_000);
        assertThat(lastDay.cancellable()).isFalse();
        assertThat(lastDay.refundPaise()).isZero();
        assertThat(lastDay.rule()).contains("24 h");

        assertThat(engine.quote(100_000, SHOW, SHOW).cancellable()).isFalse();
        assertThat(engine.quote(100_000, SHOW, SHOW.plusSeconds(60)).rule()).contains("started");
    }

    @Test
    void aZeroPercentTierMeansNotCancellable() {
        var strict = new RefundPolicyEngine(List.of(new RefundProperties.Tier(0, 0)));
        assertThat(strict.quote(100, SHOW, SHOW.minus(Duration.ofDays(10))).cancellable()).isFalse();
        assertThatThrownBy(() -> new RefundPolicyEngine(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundPolicyEngine(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
