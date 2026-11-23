package com.seatlock.assistant.refund;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Money/time formatting, the guard, the facts text and the template: pure functions, no Spring. */
class RefundAssistantUnitTest {

    private static final Instant SHOW = Instant.parse("2026-12-26T14:00:00Z");          // 7:30 pm IST
    private static final Instant TIER_END = Instant.parse("2026-12-24T14:00:00Z");      // 48 h before

    private static RefundFacts cancellable() {
        return new RefundFacts(RefundSituation.PAID_CANCELLABLE, 100_000, 100_000L, 100, 100_000L, TIER_END, 50,
                50_000L, SHOW, null, "100% back at least 48 h before the show", Set.of(100, 50));
    }

    @Test
    void rupeesUseIndianGroupingAndShowPaiseOnlyWhenNeeded() {
        assertThat(Money.rupees(80_000)).isEqualTo("₹800");
        assertThat(Money.rupees(100_000)).isEqualTo("₹1,000");
        assertThat(Money.rupees(12_345_650)).isEqualTo("₹1,23,456.50");
        assertThat(Money.rupees(1_234_567_800L)).isEqualTo("₹1,23,45,678");
        assertThat(Money.rupees(33_333)).isEqualTo("₹333.33");
        assertThat(Money.time(SHOW)).isEqualTo("Sat 26 Dec 2026, 7:30 pm IST");
    }

    @Test
    void guardAcceptsOnlyEngineAmountsAndPercentages() {
        RefundFacts f = cancellable();
        for (String ok : java.util.List.of(
                "Yes! Cancel now and you get 100% back: ₹1,000.",
                "After Thu 24 Dec 2026, 7:30 pm IST it drops to 50% (Rs. 500).",
                "You paid INR 1000.00, and 1,000 rupees comes back within 5 to 7 working days.",
                "Cancellation closes 24 hrs before the show.")) {
            assertThat(AnswerGuard.check(ok, f)).as(ok).isEmpty();
        }
        for (String bad : java.util.List.of(
                "You'll get ₹999 back.",            // not computed by the engine
                "You get 75% back.",                // not a policy percentage
                "About 12.5 percent is kept.",      // fractional percentage
                "₹1,000.5 is refunded.",            // wrong paise
                " ",
                "x".repeat(AnswerGuard.MAX_CHARS + 1))) {
            assertThat(AnswerGuard.check(bad, f)).as(bad).isPresent();
        }
        assertThat(AnswerGuard.amounts("₹1,23,456.50 and 800 rupees, Rs 5")).containsExactly(12_345_650L, 500L, 80_000L);
    }

    @Test
    void factsLinesCarryTheFormattedNumbers() {
        assertThat(String.join("\n", cancellable().lines()))
                .contains("Show starts: Sat 26 Dec 2026, 7:30 pm IST.")
                .contains("Cancelling NOW refunds 100%: ₹1,000.")
                .contains("From Thu 24 Dec 2026, 7:30 pm IST the refund drops to 50%: ₹500.");
        assertThat(cancellable().allowedAmounts()).containsExactlyInAnyOrder(100_000L, 50_000L);
        assertThat(cancellable().allowedPercents()).contains(0, 50, 100);
    }

    @Test
    void templateCoversEverySituationAndPassesItsOwnGuard() {
        RefundFacts closingNext = new RefundFacts(RefundSituation.PAID_CANCELLABLE, 100_000, 100_000L, 50, 50_000L,
                TIER_END, 0, null, SHOW, null, "50% back", Set.of(100, 50));
        RefundFacts[] all = {
                cancellable(),
                closingNext,
                simple(RefundSituation.UNPAID, Instant.parse("2026-12-20T10:08:00Z")),
                new RefundFacts(RefundSituation.PAID_CLOSED, 100_000, 100_000L, 0, null, null, null, null, SHOW, null,
                        "cancellation closes 24 h before the show", Set.of(100, 50)),
                simple(RefundSituation.EXPIRED, null),
                simple(RefundSituation.CANCELLED, null),
                simple(RefundSituation.REFUNDED, null)};
        for (RefundFacts f : all) {
            String answer = RefundTemplate.answer(f);
            assertThat(answer).as(f.situation().name()).isNotBlank();
            assertThat(AnswerGuard.check(answer, f)).as(answer).isEmpty();
            assertThat(f.lines()).isNotEmpty();
        }
        assertThat(RefundTemplate.answer(cancellable())).contains("100% back: ₹1,000 of the ₹1,000 you paid")
                .contains("drops to 50% (₹500)");
        assertThat(RefundTemplate.answer(closingNext)).contains("Cancellation closes at Thu 24 Dec 2026, 7:30 pm IST");
        assertThat(RefundTemplate.answer(simple(RefundSituation.UNPAID, Instant.parse("2026-12-20T10:08:00Z"))))
                .contains("at no cost").contains("Sun 20 Dec 2026, 3:38 pm IST");
    }

    private static RefundFacts simple(RefundSituation s, Instant payBy) {
        return new RefundFacts(s, 100_000, null, 0, null, null, null, null, SHOW, payBy, null, Set.of(100, 50));
    }
}
