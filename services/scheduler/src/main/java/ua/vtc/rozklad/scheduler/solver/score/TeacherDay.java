package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.DENOMINATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.NUMERATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.WEEK_TYPES;

import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;

/**
 * Shape of one teacher-day, the input of every teacher-day rule. A gap between two consecutive
 * occupied pairs a &lt; b of one kind of week is a <em>shift wait</em> when every lesson at a belongs to
 * a group of one shift and every lesson at b to a group of another shift (the one definition shared
 * with the core metric); every other gap is a teacher window.
 *
 * @param numeratorPairs distinct occupied pairs in the numerator week
 * @param denominatorPairs distinct occupied pairs in the denominator week
 * @param windows free pairs inside the day that are not a wait between shifts, both kinds of week
 * @param shiftWaits free pairs of waits between shifts, both kinds of week
 */
public record TeacherDay(int numeratorPairs, int denominatorPairs, int windows, int shiftWaits) {

    /** Shifts told apart by the wait rule (shift numbers modulo this value). */
    static final int SHIFT_KINDS = 4;

    /** Kinds of week with exactly one occupied pair. */
    public int onePairWeeks() {
        return (numeratorPairs == 1 ? 1 : 0) + (denominatorPairs == 1 ? 1 : 0);
    }

    /** Incremental state of one teacher-day; every add returns its undo. */
    static final class Counts {

        private final int[][] counts = new int[WEEK_TYPES][Timeslot.MAX_PAIR + 1];
        private final int[][][] byShift = new int[WEEK_TYPES][Timeslot.MAX_PAIR + 1][SHIFT_KINDS];
        private final int[] distinctPairs = new int[WEEK_TYPES];

        Runnable add(LessonUnit unit) {
            int pair = unit.getPair();
            int[] weekLanes = unit.weekLanes();
            int shift = Math.floorMod(unit.getShift(), SHIFT_KINDS);
            for (int week : weekLanes) {
                if (counts[week][pair]++ == 0) {
                    distinctPairs[week]++;
                }
                byShift[week][pair][shift]++;
            }
            return () -> {
                for (int week : weekLanes) {
                    if (--counts[week][pair] == 0) {
                        distinctPairs[week]--;
                    }
                    byShift[week][pair][shift]--;
                }
            };
        }

        TeacherDay result() {
            int windows = 0;
            int waits = 0;
            for (int week = 0; week < WEEK_TYPES; week++) {
                if (distinctPairs[week] < 2) {
                    continue;
                }
                int previous = 0;
                for (int pair = 1; pair <= Timeslot.MAX_PAIR; pair++) {
                    if (counts[week][pair] == 0) {
                        continue;
                    }
                    if (previous > 0 && pair - previous > 1) {
                        int gap = pair - previous - 1;
                        int before = pureShift(week, previous);
                        int after = pureShift(week, pair);
                        if (before >= 0 && after >= 0 && before != after) {
                            waits += gap;
                        } else {
                            windows += gap;
                        }
                    }
                    previous = pair;
                }
            }
            return new TeacherDay(distinctPairs[NUMERATOR_WEEK], distinctPairs[DENOMINATOR_WEEK], windows, waits);
        }

        /** The shift all lessons at the pair belong to, {@code -1} when they are of several shifts. */
        private int pureShift(int week, int pair) {
            int[] shifts = byShift[week][pair];
            for (int shift = 0; shift < SHIFT_KINDS; shift++) {
                if (shifts[shift] == counts[week][pair]) {
                    return shift;
                }
            }
            return -1;
        }
    }
}
