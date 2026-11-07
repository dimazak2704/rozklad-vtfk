package ua.vtc.rozklad.scheduler.solver.domain;

import java.util.Collection;

/**
 * Pairs a shift may use on each weekday (DOMAIN §2: shift 1 → pairs 1–4, shift 2 → 5–8 Mon–Thu
 * and 4–6 Fri). A weekday without a range has no lessons for the shift.
 */
public final class ShiftWindow {

    /** Pairs {@code firstPair..lastPair} of one weekday. */
    public record DayRange(int weekday, int firstPair, int lastPair) {

        public DayRange {
            if (weekday < 1 || weekday > Timeslot.MAX_WEEKDAY) {
                throw new IllegalArgumentException("weekday must be 1.." + Timeslot.MAX_WEEKDAY + ": " + weekday);
            }
            if (firstPair < 1 || lastPair > Timeslot.MAX_PAIR || firstPair > lastPair) {
                throw new IllegalArgumentException("invalid pair range " + firstPair + ".." + lastPair);
            }
        }
    }

    // індекс — день тижня (1..5); 0 означає, що зміна цього дня не працює
    private final int[] firstPair = new int[Timeslot.MAX_WEEKDAY + 1];
    private final int[] lastPair = new int[Timeslot.MAX_WEEKDAY + 1];

    public ShiftWindow(Collection<DayRange> days) {
        for (DayRange day : days) {
            if (firstPair[day.weekday()] != 0) {
                throw new IllegalArgumentException("duplicate range for weekday " + day.weekday());
            }
            firstPair[day.weekday()] = day.firstPair();
            lastPair[day.weekday()] = day.lastPair();
        }
    }

    public boolean contains(Timeslot timeslot) {
        int first = firstPair[timeslot.weekday()];
        return first != 0 && timeslot.pair() >= first && timeslot.pair() <= lastPair[timeslot.weekday()];
    }

    /**
     * 0-based position of the pair counted from the first pair of the shift on that weekday
     * (from pair 1 when the shift has no lessons that day).
     */
    public int positionOf(Timeslot timeslot) {
        int first = firstPair[timeslot.weekday()];
        return timeslot.pair() - (first == 0 ? 1 : first);
    }
}
