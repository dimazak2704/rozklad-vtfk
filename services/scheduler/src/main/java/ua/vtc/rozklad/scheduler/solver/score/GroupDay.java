package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.DENOMINATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.NUMERATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.WEEK_TYPES;

import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.StudentGroup;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;

/**
 * Shape of one group-day, the input of every group-day rule.
 *
 * @param windows free pairs between lessons: per kind of week the worst cohort (B10, the same as the core
 *                metric), summed over both kinds of week, except a <em>lone subgroup pair at the edge of the
 *                day</em>: a pair where another subgroup studies, between the pairs with every cohort at a lesson and
 *                the cohort's own lessons on that side of them (one teacher's two subgroups one after the other at
 *                the start or the end of the day, a subgroup lesson without a counterpart at the first or last
 *                pair). A free pair between two pairs of the whole group, between two of the cohort's own lessons
 *                on one side of them (sub 1, sub 2, sub 1), or on a day without such a pair stays a window for the
 *                subgroup that waits.
 * @param brokenness «рваний день»: |Δ pair count| + 1 if the first pair differs (union of cohorts)
 * @param onePairWeeks kinds of week in which the group has exactly one occupied pair (union of cohorts)
 * @param loneSubgroupSlots pair × kind of week where a subgroup studies while another cohort has no lesson
 * @param pairsAboveDayLimit occupied pairs (union of cohorts) above the group's limit for the weekday (Friday),
 *                           summed over both kinds of week
 * @param hasFreeUnit the solver may change the day: at least one of its lessons is not pinned. The hard rules over
 *                    the day (windows, the Friday limit) judge only such days, so lessons pinned outside the scope
 *                    never make a run infeasible.
 */
public record GroupDay(int windows, int brokenness, int onePairWeeks, int loneSubgroupSlots, int pairsAboveDayLimit,
                       boolean hasFreeUnit) {

    /** Windows the hard rule counts: those of a day the run may change. */
    public int hardWindows() {
        return hasFreeUnit ? windows : 0;
    }

    /** Pairs above the day limit the hard rule counts: those of a day the run may change. */
    public int hardPairsAboveDayLimit() {
        return hasFreeUnit ? pairsAboveDayLimit : 0;
    }

    /** Incremental state of one group-day; every add returns its undo. */
    static final class Counts {

        private final LaneCounts cohorts = new LaneCounts(LaneCounts.COHORT_LANES);
        private final LaneCounts weeks = new LaneCounts(WEEK_TYPES);
        private final LaneCounts subgroupLessons = new LaneCounts(WEEK_TYPES);
        private int cohortCount = 1;
        private StudentGroup group;
        private int weekday;
        private int freeUnits;

        Runnable add(LessonUnit unit) {
            // значення фіксуємо зараз: на момент відкату заняття вже може стояти деінде
            int pair = unit.getPair();
            int[] cohortLanes = unit.cohortLanes();
            int[] weekLanes = unit.weekLanes();
            boolean subgroup = unit.getSubgroup() != 0;
            boolean free = !unit.isPinned();
            group = unit.getGroup();
            weekday = unit.getWeekday();
            cohortCount = group.cohortCount();
            cohorts.add(cohortLanes, pair);
            weeks.add(weekLanes, pair);
            if (subgroup) {
                subgroupLessons.add(weekLanes, pair);
            }
            if (free) {
                freeUnits++;
            }
            return () -> {
                cohorts.remove(cohortLanes, pair);
                weeks.remove(weekLanes, pair);
                if (subgroup) {
                    subgroupLessons.remove(weekLanes, pair);
                }
                if (free) {
                    freeUnits--;
                }
            };
        }

        GroupDay result() {
            int windows = 0;
            int onePairWeeks = 0;
            int lone = 0;
            int aboveLimit = 0;
            for (int week = 0; week < WEEK_TYPES; week++) {
                boolean subgroups = subgroupLessons.distinctPairs(week) > 0;
                windows += subgroups ? worstCohortWindows(week) : cohorts.gaps(week);
                int pairs = weeks.distinctPairs(week);
                if (pairs == 1) {
                    onePairWeeks++;
                }
                if (subgroups) {
                    lone += loneSlots(week);
                }
                aboveLimit += group.pairsAboveDayLimit(weekday, pairs);
            }
            WeekBalance balance = new WeekBalance(
                    weeks.distinctPairs(NUMERATOR_WEEK), weeks.firstPair(NUMERATOR_WEEK),
                    weeks.distinctPairs(DENOMINATOR_WEEK), weeks.firstPair(DENOMINATOR_WEEK));
            return new GroupDay(windows, balance.brokenness(), onePairWeeks, lone, aboveLimit, freeUnits > 0);
        }

        /**
         * The worst cohort's free pairs between its lessons in a week with subgroup lessons. A free pair where another
         * subgroup studies does not count when it lies between the block of pairs with every cohort at a lesson and
         * the cohort's own nearest lesson on that side of the block (the lone subgroup pairs at the edge of the day);
         * between two own lessons on one side of the block, inside the block, or on a day without a full pair it
         * counts. Without subgroup lessons every cohort has the same pairs, so the caller takes the gaps of cohort 0.
         */
        private int worstCohortWindows(int week) {
            int fullFirst = 0;
            int fullLast = 0;
            for (int pair = 1; pair <= Timeslot.MAX_PAIR; pair++) {
                if (weeks.count(week, pair) > 0 && everyCohortAt(week, pair)) {
                    if (fullFirst == 0) {
                        fullFirst = pair;
                    }
                    fullLast = pair;
                }
            }
            int worst = 0;
            for (int cohort = 0; cohort < cohortCount; cohort++) {
                int lane = cohort * WEEK_TYPES + week;
                if (cohorts.distinctPairs(lane) < 2) {
                    continue;
                }
                // без повної пари крайових немає: межі 0 нічого не охоплюють
                int edgeBefore = 0;
                int edgeAfter = 0;
                if (fullFirst > 0) {
                    edgeBefore = ownLessonBelow(lane, fullFirst);
                    edgeAfter = ownLessonAbove(lane, fullLast);
                }
                int last = cohorts.lastPair(lane);
                int free = 0;
                for (int pair = cohorts.firstPair(lane) + 1; pair < last; pair++) {
                    boolean edge = weeks.count(week, pair) > 0
                            && (edgeBefore < pair && pair < fullFirst || fullLast < pair && pair < edgeAfter);
                    if (cohorts.count(lane, pair) == 0 && !edge) {
                        free++;
                    }
                }
                worst = Math.max(worst, free);
            }
            return worst;
        }

        /** The cohort's last lesson before {@code pair}, 0 without one. */
        private int ownLessonBelow(int lane, int pair) {
            for (int below = pair - 1; below > 0; below--) {
                if (cohorts.count(lane, below) > 0) {
                    return below;
                }
            }
            return 0;
        }

        /** The cohort's first lesson after {@code pair}, past the last pair without one. */
        private int ownLessonAbove(int lane, int pair) {
            for (int above = pair + 1; above <= Timeslot.MAX_PAIR; above++) {
                if (cohorts.count(lane, above) > 0) {
                    return above;
                }
            }
            return Timeslot.MAX_PAIR + 1;
        }

        private boolean everyCohortAt(int week, int pair) {
            for (int cohort = 0; cohort < cohortCount; cohort++) {
                if (cohorts.count(cohort * WEEK_TYPES + week, pair) == 0) {
                    return false;
                }
            }
            return true;
        }

        private int loneSlots(int week) {
            int lone = 0;
            for (int pair = 1; pair <= Timeslot.MAX_PAIR; pair++) {
                if (subgroupLessons.count(week, pair) > 0) {
                    for (int cohort = 0; cohort < cohortCount; cohort++) {
                        if (cohorts.count(cohort * WEEK_TYPES + week, pair) == 0) {
                            lone++;
                            break;
                        }
                    }
                }
            }
            return lone;
        }
    }
}
