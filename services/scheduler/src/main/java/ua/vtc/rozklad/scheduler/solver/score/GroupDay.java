package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.DENOMINATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.NUMERATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.WEEK_TYPES;

import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;

/**
 * Shape of one group-day, the input of every group-day rule.
 *
 * @param windows free pairs between lessons: per kind of week the worst cohort (B10, the same as the
 *                core metric), summed over both kinds of week
 * @param brokenness «рваний день»: |Δ pair count| + 1 if the first pair differs (union of cohorts)
 * @param onePairWeeks kinds of week in which the group has exactly one occupied pair (union of cohorts)
 * @param loneSubgroupSlots pair × kind of week where a subgroup studies while another cohort has no lesson
 */
public record GroupDay(int windows, int brokenness, int onePairWeeks, int loneSubgroupSlots) {

    /** Incremental state of one group-day; every add returns its undo. */
    static final class Counts {

        private final LaneCounts cohorts = new LaneCounts(LaneCounts.COHORT_LANES);
        private final LaneCounts weeks = new LaneCounts(WEEK_TYPES);
        private final LaneCounts subgroupLessons = new LaneCounts(WEEK_TYPES);
        private int cohortCount = 1;

        Runnable add(LessonUnit unit) {
            // значення фіксуємо зараз: на момент відкату заняття вже може стояти деінде
            int pair = unit.getPair();
            int[] cohortLanes = unit.cohortLanes();
            int[] weekLanes = unit.weekLanes();
            boolean subgroup = unit.getSubgroup() != 0;
            cohortCount = unit.getGroup().cohortCount();
            cohorts.add(cohortLanes, pair);
            weeks.add(weekLanes, pair);
            if (subgroup) {
                subgroupLessons.add(weekLanes, pair);
            }
            return () -> {
                cohorts.remove(cohortLanes, pair);
                weeks.remove(weekLanes, pair);
                if (subgroup) {
                    subgroupLessons.remove(weekLanes, pair);
                }
            };
        }

        GroupDay result() {
            int windows = 0;
            int onePairWeeks = 0;
            int lone = 0;
            for (int week = 0; week < WEEK_TYPES; week++) {
                int worst = 0;
                for (int cohort = 0; cohort < cohortCount; cohort++) {
                    worst = Math.max(worst, cohorts.gaps(cohort * WEEK_TYPES + week));
                }
                windows += worst;
                if (weeks.distinctPairs(week) == 1) {
                    onePairWeeks++;
                }
                if (subgroupLessons.distinctPairs(week) > 0) {
                    lone += loneSlots(week);
                }
            }
            WeekBalance balance = new WeekBalance(
                    weeks.distinctPairs(NUMERATOR_WEEK), weeks.firstPair(NUMERATOR_WEEK),
                    weeks.distinctPairs(DENOMINATOR_WEEK), weeks.firstPair(DENOMINATOR_WEEK));
            return new GroupDay(windows, balance.brokenness(), onePairWeeks, lone);
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
