package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.DENOMINATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.NUMERATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.WEEK_TYPES;

import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;

/**
 * Pairs of one discipline in one group-day.
 *
 * @param numeratorPairs distinct pairs of the group (subgroups in one pair count once), numerator week
 * @param denominatorPairs the same in the denominator week
 * @param numeratorCohortPairs pairs the busiest cohort attends, numerator week: two subgroups of one
 *                             teacher one after the other are one pair for each student
 * @param denominatorCohortPairs the same in the denominator week
 */
public record DisciplineDay(int numeratorPairs, int denominatorPairs, int numeratorCohortPairs,
                            int denominatorCohortPairs) {

    /** Pairs of the group above {@code limit} in each kind of week, summed. */
    public int excessOver(int limit) {
        return Math.max(0, numeratorPairs - limit) + Math.max(0, denominatorPairs - limit);
    }

    /** Pairs a student attends above {@code limit} in each kind of week (worst cohort), summed. */
    public int cohortExcessOver(int limit) {
        return Math.max(0, numeratorCohortPairs - limit) + Math.max(0, denominatorCohortPairs - limit);
    }

    /** Incremental state of one group-day-discipline; every add returns its undo. */
    static final class Counts {

        private final LaneCounts cohorts = new LaneCounts(LaneCounts.COHORT_LANES);
        private final LaneCounts weeks = new LaneCounts(WEEK_TYPES);
        private int cohortCount = 1;

        Runnable add(LessonUnit unit) {
            int pair = unit.getPair();
            int[] cohortLanes = unit.cohortLanes();
            int[] weekLanes = unit.weekLanes();
            cohortCount = unit.getGroup().cohortCount();
            cohorts.add(cohortLanes, pair);
            weeks.add(weekLanes, pair);
            return () -> {
                cohorts.remove(cohortLanes, pair);
                weeks.remove(weekLanes, pair);
            };
        }

        DisciplineDay result() {
            return new DisciplineDay(weeks.distinctPairs(NUMERATOR_WEEK), weeks.distinctPairs(DENOMINATOR_WEEK),
                    busiestCohort(NUMERATOR_WEEK), busiestCohort(DENOMINATOR_WEEK));
        }

        private int busiestCohort(int week) {
            int busiest = 0;
            for (int cohort = 0; cohort < cohortCount; cohort++) {
                busiest = Math.max(busiest, cohorts.distinctPairs(cohort * WEEK_TYPES + week));
            }
            return busiest;
        }
    }
}
