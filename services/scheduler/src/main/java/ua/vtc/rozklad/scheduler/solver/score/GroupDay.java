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
 *                day</em>: a pair where another subgroup studies and no pair before it, or none after it, has every
 *                cohort at a lesson (one teacher's two subgroups one after the other at the start or the end of the
 *                day, a subgroup lesson without a counterpart at the first or last pair). A lone pair between two
 *                pairs of the whole group stays a window for the subgroup that waits.
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
         * The worst cohort's free pairs between its lessons in a week with subgroup lessons; a free pair where another
         * subgroup studies does not count when it lies before the first or after the last pair with every cohort at a
         * lesson (the lone subgroup pairs at the edge of the day). Without subgroup lessons every cohort has the same
         * pairs, so the caller takes the gaps of cohort 0.
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
                int last = cohorts.lastPair(lane);
                int free = 0;
                for (int pair = cohorts.firstPair(lane) + 1; pair < last; pair++) {
                    if (cohorts.count(lane, pair) == 0 && !isLoneAtTheEdge(week, pair, fullFirst, fullLast)) {
                        free++;
                    }
                }
                worst = Math.max(worst, free);
            }
            return worst;
        }

        /** Another cohort studies at the pair, and no pair with every cohort at a lesson lies on one side of it. */
        private boolean isLoneAtTheEdge(int week, int pair, int fullFirst, int fullLast) {
            return weeks.count(week, pair) > 0 && (fullFirst == 0 || pair < fullFirst || pair > fullLast);
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
