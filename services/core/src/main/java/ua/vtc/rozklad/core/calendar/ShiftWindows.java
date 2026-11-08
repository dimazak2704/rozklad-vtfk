package ua.vtc.rozklad.core.calendar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pairs each shift may use per weekday (table {@code shift_window}). When the table is empty the
 * rule of DOMAIN.md §2 applies: shift 1 → pairs 1–4 Monday–Friday; shift 2 → 5–8 Monday–Thursday
 * and 4–6 on Friday. A weekday without a row is not restricted.
 */
public final class ShiftWindows {

    private final Map<Integer, Row> rows;
    private final boolean fromDomainDefaults;

    private ShiftWindows(Collection<Row> rows, boolean fromDomainDefaults) {
        Map<Integer, Row> byKey = new TreeMap<>();
        rows.forEach(row -> byKey.put(key(row.shift(), row.weekday()), row));
        this.rows = byKey;
        this.fromDomainDefaults = fromDomainDefaults;
    }

    public static ShiftWindows of(Collection<ShiftWindow> windows) {
        if (windows.isEmpty()) {
            return domainDefaults();
        }
        return new ShiftWindows(windows.stream()
                .map(window -> new Row(window.getShift(), window.getWeekday(), window.getFirstPair(),
                        window.getLastPair()))
                .toList(), false);
    }

    public static ShiftWindows domainDefaults() {
        List<Row> rows = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            rows.add(new Row(1, weekday, 1, 4));
            rows.add(weekday == 5 ? new Row(2, weekday, 4, 6) : new Row(2, weekday, 5, 8));
        }
        return new ShiftWindows(rows, true);
    }

    public Optional<PairRange> window(int shift, int weekday) {
        return Optional.ofNullable(rows.get(key(shift, weekday))).map(row -> new PairRange(row.firstPair(),
                row.lastPair()));
    }

    /** Every window ordered by shift and weekday (for the generation snapshot). */
    public List<Row> rows() {
        return rows.values().stream()
                .sorted(Comparator.comparingInt(Row::shift).thenComparingInt(Row::weekday))
                .toList();
    }

    /** {@code true} when the table was empty and the DOMAIN.md default windows are used. */
    public boolean fromDomainDefaults() {
        return fromDomainDefaults;
    }

    private static int key(int shift, int weekday) {
        return shift * 10 + weekday;
    }

    public record Row(int shift, int weekday, int firstPair, int lastPair) {
    }

    /** Inclusive range of pair numbers. */
    public record PairRange(int first, int last) {

        public boolean contains(int pair) {
            return pair >= first && pair <= last;
        }
    }
}
