package com.seatlock.assistant.seatfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Finds the best {@value #MAX_OPTIONS} groups of {@code count} adjacent available seats. Pure Java, no I/O,
 * deterministic: the same layout and preferences always give the same options.
 * <ol>
 *   <li><b>Filter</b>: available seats within budget and section.</li>
 *   <li><b>Runs</b>: per row, sorted by column, split wherever columns aren't consecutive (aisle, taken or
 *       filtered seat).</li>
 *   <li><b>Sliding window</b> of size {@code count} over each run: every window is a candidate (O(seats)).</li>
 *   <li><b>Score</b> 0–100: 45 % centre of the row, 45 % row depth per {@link RowPreference}, 10 % price under
 *       budget (no budget → no price term).</li>
 *   <li><b>Pick</b> the best, then the next best that shares no seat with those picked, up to 3. Ties: front row
 *       first, then leftmost.</li>
 * </ol>
 * It only suggests: holding is a separate, atomic step (HoldService), so a suggestion that's taken meanwhile is
 * simply rejected there.
 */
public final class SeatFinder {

    public static final int MAX_OPTIONS = 3;

    static final double CENTRE_WEIGHT = 0.45;
    static final double ROW_WEIGHT = 0.45;
    static final double PRICE_WEIGHT = 0.10;
    /** {@link RowPreference#ANY}: the best view is a bit behind the middle of the front half. */
    static final double PREFERRED_DEPTH = 0.4;

    public List<SeatOption> find(SeatLayout layout, SeatPreferences prefs) {
        TreeMap<Integer, IntSummaryStatistics> rowSpans = layout.seats().stream().collect(Collectors.groupingBy(
                SeatLayout.Seat::gridRow, TreeMap::new, Collectors.summarizingInt(SeatLayout.Seat::gridCol)));
        if (rowSpans.isEmpty()) {
            return List.of();
        }
        int firstRow = rowSpans.firstKey();
        int lastRow = rowSpans.lastKey();

        Map<Integer, List<SeatLayout.Seat>> eligibleByRow = layout.seats().stream()
                .filter(SeatLayout.Seat::available)
                .filter(s -> prefs.maxPricePaise() == null || s.pricePaise() <= prefs.maxPricePaise())
                .filter(s -> prefs.sections().isEmpty() || prefs.sections().contains(s.section()))
                .collect(Collectors.groupingBy(SeatLayout.Seat::gridRow, TreeMap::new, Collectors.toList()));

        List<Candidate> candidates = new ArrayList<>();
        eligibleByRow.forEach((row, seats) -> {
            double rowScore = rowScore(row, firstRow, lastRow, prefs.rows());
            IntSummaryStatistics span = rowSpans.get(row);
            for (List<SeatLayout.Seat> run : runs(seats)) {
                for (int start = 0; start + prefs.count() <= run.size(); start++) {
                    List<SeatLayout.Seat> window = run.subList(start, start + prefs.count());
                    if (prefs.aisle() && window.stream().noneMatch(SeatLayout.Seat::aisle)) {
                        continue;
                    }
                    candidates.add(score(window, span, rowScore, prefs));
                }
            }
        });

        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparingInt(Candidate::gridRow)
                .thenComparingInt(Candidate::firstCol));
        return pick(candidates);
    }

    /** Splits one row's seats (any order) into runs of consecutive columns. */
    static List<List<SeatLayout.Seat>> runs(List<SeatLayout.Seat> rowSeats) {
        List<SeatLayout.Seat> sorted = rowSeats.stream().sorted(Comparator.comparingInt(SeatLayout.Seat::gridCol)).toList();
        List<List<SeatLayout.Seat>> runs = new ArrayList<>();
        List<SeatLayout.Seat> current = new ArrayList<>();
        for (SeatLayout.Seat seat : sorted) {
            if (!current.isEmpty() && seat.gridCol() != current.getLast().gridCol() + 1) {
                runs.add(current);
                current = new ArrayList<>();
            }
            current.add(seat);
        }
        if (!current.isEmpty()) {
            runs.add(current);
        }
        return runs;
    }

    /** 0..1 by how far back the row is: 0 = first row, 1 = last row. */
    static double rowScore(int row, int firstRow, int lastRow, RowPreference preference) {
        double depth = lastRow == firstRow ? 0 : (double) (row - firstRow) / (lastRow - firstRow);
        return switch (preference) {
            case FRONT -> 1 - depth;
            case BACK -> depth;
            case MIDDLE -> 1 - Math.abs(depth - 0.5) * 2;
            case ANY -> 1 - Math.min(1, Math.abs(depth - PREFERRED_DEPTH) / (1 - PREFERRED_DEPTH));
        };
    }

    private static Candidate score(List<SeatLayout.Seat> window, IntSummaryStatistics rowSpan, double rowScore,
                                   SeatPreferences prefs) {
        double rowMiddle = (rowSpan.getMin() + rowSpan.getMax()) / 2.0;
        double halfWidth = Math.max(1, (rowSpan.getMax() - rowSpan.getMin()) / 2.0);
        double groupMiddle = (window.getFirst().gridCol() + window.getLast().gridCol()) / 2.0;
        double centre = 1 - Math.min(1, Math.abs(groupMiddle - rowMiddle) / halfWidth);

        long total = 0;
        for (SeatLayout.Seat seat : window) {
            total = Math.addExact(total, seat.pricePaise());
        }
        double price = prefs.maxPricePaise() == null
                ? 0
                : 1 - (double) total / ((double) prefs.maxPricePaise() * window.size());

        int score = (int) Math.round(100 * (CENTRE_WEIGHT * centre + ROW_WEIGHT * rowScore + PRICE_WEIGHT * price));
        return new Candidate(List.copyOf(window), score, total);
    }

    /** Best first, skipping any candidate that shares a seat with one already picked. */
    private static List<SeatOption> pick(List<Candidate> sorted) {
        List<SeatOption> options = new ArrayList<>(MAX_OPTIONS);
        Set<Long> used = new HashSet<>();
        for (Candidate c : sorted) {
            if (options.size() == MAX_OPTIONS) {
                break;
            }
            if (c.seats().stream().anyMatch(s -> used.contains(s.seatId()))) {
                continue;
            }
            c.seats().forEach(s -> used.add(s.seatId()));
            options.add(new SeatOption(options.size() + 1, c.score(), c.seats().getFirst().rowLabel(),
                    c.seats().stream().map(SeatLayout.Seat::section).distinct().toList(),
                    c.seats(), c.totalPricePaise(), c.seats().stream().anyMatch(SeatLayout.Seat::aisle)));
        }
        return options;
    }

    private record Candidate(List<SeatLayout.Seat> seats, int score, long totalPricePaise) {
        int gridRow() {
            return seats.getFirst().gridRow();
        }

        int firstCol() {
            return seats.getFirst().gridCol();
        }
    }
}
