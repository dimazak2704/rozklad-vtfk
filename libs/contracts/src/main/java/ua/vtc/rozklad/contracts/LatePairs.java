package ua.vtc.rozklad.contracts;

/**
 * The one definition of a «4-та пара зміни» (late pair), shared by the scheduler's soft constraint, the
 * core metric and the web labels: the 4th pair of the group's shift window and later. Shift 1: the 4th
 * pair (12:10–13:10); shift 2 Monday–Thursday: the 8th pair; Friday shift 2 (pairs 4–6) has none.
 *
 * <p>It is not a college rule: the real timetable uses the 4th pair of the shift on 321 of 560 group
 * day-weeks (57 %). The weight only spreads pairs across the week.
 */
public final class LatePairs {

    /** 0-based position in the group's shift window from which a pair counts: the 4th pair of the window. */
    public static final int FIRST_LATE_POSITION = 3;

    private LatePairs() {
    }

    public static boolean isLate(int pair, int windowFirstPair) {
        return pair - windowFirstPair >= FIRST_LATE_POSITION;
    }
}
