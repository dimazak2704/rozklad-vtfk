package ua.vtc.rozklad.scheduler.solver.domain;

import java.util.Comparator;

/**
 * A weekly slot of the timetable: weekday 1 (Monday)..{@value #MAX_WEEKDAY} (Friday) and pair
 * number 1..{@value #MAX_PAIR}. Problem fact and planning value.
 */
public record Timeslot(int weekday, int pair) implements Comparable<Timeslot> {

    public static final int MAX_WEEKDAY = 5;
    public static final int MAX_PAIR = 12;

    private static final Comparator<Timeslot> ORDER =
            Comparator.comparingInt(Timeslot::weekday).thenComparingInt(Timeslot::pair);

    public Timeslot {
        if (weekday < 1 || weekday > MAX_WEEKDAY) {
            throw new IllegalArgumentException("weekday must be 1.." + MAX_WEEKDAY + ": " + weekday);
        }
        if (pair < 1 || pair > MAX_PAIR) {
            throw new IllegalArgumentException("pair must be 1.." + MAX_PAIR + ": " + pair);
        }
    }

    @Override
    public int compareTo(Timeslot other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return weekday + "/" + pair;
    }
}
