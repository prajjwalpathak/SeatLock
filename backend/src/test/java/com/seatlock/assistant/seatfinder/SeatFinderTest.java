package com.seatlock.assistant.seatfinder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Venue like the seed: each row has 8 seats, columns 0–3 | aisle gap (col 4) | 5–8; seats 4 and 5 are aisle
 * seats. Rows 0–1 PLATINUM (₹1,000), 2–3 GOLD (₹700), 4–5 SILVER (₹500).
 */
class SeatFinderTest {

    private final SeatFinder finder = new SeatFinder();

    private static List<SeatLayout.Seat> hall(int rows, Set<Long> taken) {
        List<SeatLayout.Seat> seats = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            String section = r < 2 ? "PLATINUM" : r < 4 ? "GOLD" : "SILVER";
            long price = r < 2 ? 100_000 : r < 4 ? 70_000 : 50_000;
            for (int n = 1; n <= 8; n++) {
                long id = r * 100L + n;
                int col = n <= 4 ? n - 1 : n;
                seats.add(new SeatLayout.Seat(id, section, String.valueOf((char) ('A' + r)), n, r, col,
                        n == 4 || n == 5, price, !taken.contains(id)));
            }
        }
        return seats;
    }

    private static SeatLayout layout(int rows, Long... taken) {
        return new SeatLayout(hall(rows, Set.of(taken)));
    }

    private static SeatPreferences prefs(int count) {
        return new SeatPreferences(count, null, null, false, null);
    }

    private static List<Integer> cols(SeatOption option) {
        return option.seats().stream().map(SeatLayout.Seat::gridCol).toList();
    }

    @Test
    void groupsNeverSpanTheAisle() {
        assertThat(finder.find(layout(6), prefs(5))).isEmpty();          // longest run is 4
        assertThat(finder.find(layout(6), prefs(4))).allSatisfy(o -> assertThat(cols(o)).isIn(
                List.of(0, 1, 2, 3), List.of(5, 6, 7, 8)));
    }

    @Test
    void aTakenSeatSplitsTheRun() {
        // one row; seat 2 (col 1) taken → left block has runs [0] and [2,3]; only the right block fits 3
        List<SeatOption> options = finder.find(layout(1, 2L), prefs(3));

        assertThat(options).isNotEmpty().allSatisfy(o -> assertThat(o.seats()).allSatisfy(s -> assertThat(s.gridCol()).isGreaterThan(4)));
        assertThat(options).noneSatisfy(o -> assertThat(o.seatIds()).contains(2L));
    }

    @Test
    void centreSeatsWinAndTiesGoLeftThenOptionsDoNotOverlap() {
        List<SeatOption> options = finder.find(layout(1), prefs(2));

        assertThat(cols(options.get(0))).containsExactly(2, 3);   // centre distance 1.5, leftmost on the tie
        assertThat(cols(options.get(1))).containsExactly(5, 6);   // same distance, right of the aisle
        assertThat(options).extracting(SeatOption::rank).containsExactly(1, 2, 3);
        Set<Long> seen = new HashSet<>();
        options.forEach(o -> o.seatIds().forEach(id -> assertThat(seen.add(id)).isTrue()));
        assertThat(options).extracting(SeatOption::score).isSortedAccordingTo((a, b) -> b - a);
    }

    @Test
    void scoreIsTheDocumentedWeightedSum() {
        // single row: depth 0 → ANY row score 1 - 0.4/0.6 = 1/3; cols 2–3: centre 1 - 1.5/4 = 0.625
        SeatOption best = finder.find(layout(1), prefs(2)).getFirst();
        assertThat(best.score()).isEqualTo(43);                     // round(100 × (0.45 × 0.625 + 0.45 / 3))

        // budget twice the price: price term 1 - 2000/4000 = 0.5 → +5
        SeatOption withBudget = finder.find(layout(1),
                new SeatPreferences(2, 200_000L, null, false, null)).getFirst();
        assertThat(withBudget.score()).isEqualTo(48);
    }

    @Test
    void rowPreferenceMovesTheBestOption() {
        assertThat(best(RowPreference.FRONT).rowLabel()).isEqualTo("A");
        assertThat(best(RowPreference.BACK).rowLabel()).isEqualTo("F");
        assertThat(best(RowPreference.MIDDLE).rowLabel()).isIn("C", "D");   // depth 0.4 / 0.6 are equally middle
        assertThat(best(RowPreference.ANY).rowLabel()).isEqualTo("C");      // depth 0.4 = preferred view
    }

    private SeatOption best(RowPreference rows) {
        return finder.find(layout(6), new SeatPreferences(2, null, null, false, rows)).getFirst();
    }

    @Test
    void budgetIsAPerSeatHardLimit() {
        List<SeatOption> options = finder.find(layout(6), new SeatPreferences(2, 70_000L, null, false, RowPreference.FRONT));

        assertThat(options).hasSize(3).allSatisfy(o ->
                assertThat(o.seats()).allSatisfy(s -> assertThat(s.pricePaise()).isLessThanOrEqualTo(70_000)));
        assertThat(options.getFirst().rowLabel()).isEqualTo("C");   // front-most row within budget
        assertThat(finder.find(layout(6), new SeatPreferences(1, 10_000L, null, false, null))).isEmpty();
    }

    @Test
    void sectionsFilter() {
        assertThat(finder.find(layout(6), new SeatPreferences(2, null, Set.of("SILVER"), false, null)))
                .isNotEmpty()
                .allSatisfy(o -> assertThat(o.sections()).containsExactly("SILVER"));
    }

    @Test
    void aisleRequestKeepsOnlyGroupsWithAnAisleSeat() {
        List<SeatOption> options = finder.find(layout(6), new SeatPreferences(3, null, null, true, null));

        assertThat(options).hasSize(3).allSatisfy(o -> {
            assertThat(o.hasAisleSeat()).isTrue();
            assertThat(cols(o).contains(3) || cols(o).contains(5)).as("touches the aisle (col 3 or 5)").isTrue();
        });
    }

    @Test
    void optionCarriesTotalSeatsLeftToRightAndRow() {
        SeatOption option = finder.find(layout(1), prefs(3)).getFirst();

        assertThat(option.totalPricePaise()).isEqualTo(300_000);
        assertThat(option.seats()).extracting(SeatLayout.Seat::seatNumber).isSorted();
        assertThat(option.rowLabel()).isEqualTo("A");
        assertThat(option.sections()).containsExactly("PLATINUM");
    }

    @Test
    void fullHallOrEmptyLayoutGivesNothing() {
        Long[] all = hall(1, Set.of()).stream().map(SeatLayout.Seat::seatId).toArray(Long[]::new);
        assertThat(finder.find(layout(1, all), prefs(1))).isEmpty();
        assertThat(finder.find(new SeatLayout(List.of()), prefs(1))).isEmpty();
    }

    @Test
    void runsSplitOnGapsWhateverTheInputOrder() {
        List<SeatLayout.Seat> row = new ArrayList<>(hall(1, Set.of()));
        java.util.Collections.reverse(row);
        assertThat(SeatFinder.runs(row)).extracting(List::size).containsExactly(4, 4);
    }

    @Test
    void rowScoresStayWithinZeroAndOne() {
        for (RowPreference p : RowPreference.values()) {
            for (int r = 0; r <= 9; r++) {
                assertThat(SeatFinder.rowScore(r, 0, 9, p)).isBetween(0.0, 1.0);
            }
            assertThat(SeatFinder.rowScore(3, 3, 3, p)).isBetween(0.0, 1.0);   // single row
        }
    }

    @Test
    void preferencesValidateAndDefault() {
        assertThatThrownBy(() -> new SeatPreferences(0, null, null, false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeatPreferences(2, 0L, null, false, null)).isInstanceOf(IllegalArgumentException.class);
        SeatPreferences p = prefs(2);
        assertThat(p.sections()).isEmpty();
        assertThat(p.rows()).isEqualTo(RowPreference.ANY);
    }
}
