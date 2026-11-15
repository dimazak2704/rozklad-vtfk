package ua.vtc.rozklad.scheduler.solver.score;

import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.StudentGroup;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;

/**
 * Mutable accumulator of one day: how many lessons occupy each pair in each lane. A lane is a
 * week type (numerator/denominator), optionally per student cohort. Used inside constraint
 * collectors only; every add is undone by the matching remove.
 */
final class LaneCounts {

    /** Lanes for «student cohort × week type»: lane = cohort × {@link LessonUnit#WEEK_TYPES} + week. */
    static final int COHORT_LANES = StudentGroup.MAX_COHORTS * LessonUnit.WEEK_TYPES;

    private final int[][] counts;
    private final int[] distinctPairs;

    LaneCounts(int lanes) {
        this.counts = new int[lanes][Timeslot.MAX_PAIR + 1];
        this.distinctPairs = new int[lanes];
    }

    void add(int[] lanes, int pair) {
        for (int lane : lanes) {
            if (counts[lane][pair]++ == 0) {
                distinctPairs[lane]++;
            }
        }
    }

    void remove(int[] lanes, int pair) {
        for (int lane : lanes) {
            if (--counts[lane][pair] == 0) {
                distinctPairs[lane]--;
            }
        }
    }

    /** Lessons of the lane at a pair. */
    int count(int lane, int pair) {
        return counts[lane][pair];
    }

    /** Number of pairs with at least one lesson in the lane. */
    int distinctPairs(int lane) {
        return distinctPairs[lane];
    }

    /** First occupied pair of the lane, 0 when the lane is empty. */
    int firstPair(int lane) {
        if (distinctPairs[lane] == 0) {
            return 0;
        }
        int[] pairs = counts[lane];
        for (int pair = 1; pair < pairs.length; pair++) {
            if (pairs[pair] > 0) {
                return pair;
            }
        }
        throw new IllegalStateException("lane " + lane + " is inconsistent");
    }

    /** Last occupied pair of the lane, 0 when the lane is empty. */
    int lastPair(int lane) {
        if (distinctPairs[lane] == 0) {
            return 0;
        }
        int[] pairs = counts[lane];
        for (int pair = pairs.length - 1; pair > 0; pair--) {
            if (pairs[pair] > 0) {
                return pair;
            }
        }
        throw new IllegalStateException("lane " + lane + " is inconsistent");
    }

    /** Free pairs between the first and the last lesson of one lane ("windows"). */
    int gaps(int lane) {
        int distinct = distinctPairs[lane];
        if (distinct < 2) {
            return 0;
        }
        int[] pairs = counts[lane];
        int first = 1;
        while (pairs[first] == 0) {
            first++;
        }
        int last = pairs.length - 1;
        while (pairs[last] == 0) {
            last--;
        }
        return last - first + 1 - distinct;
    }
}
