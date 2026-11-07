package ua.vtc.rozklad.scheduler.solver.score;

import ai.timefold.solver.core.api.score.stream.uni.UniConstraintCollector;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;

/**
 * Incremental collectors over the lessons of one day (grouped by group or teacher and weekday).
 * Each lesson adds its pair to the lanes it occupies; the collector undoes exactly that when the
 * lesson moves, so the values stay correct during local search.
 */
public final class DayCollectors {

    private DayCollectors() {
    }

    /** Windows of the students: per cohort and week type (a whole-group lesson counts for all cohorts). */
    public static UniConstraintCollector<LessonUnit, ?, Integer> cohortGaps() {
        return new LaneCollector<>(LaneCounts.COHORT_LANES, LessonUnit::cohortLanes, LaneCounts::gaps);
    }

    /** Windows per week type, e.g. of a teacher's day. */
    public static UniConstraintCollector<LessonUnit, ?, Integer> weekGaps() {
        return new LaneCollector<>(LessonUnit.WEEK_TYPES, LessonUnit::weekLanes, LaneCounts::gaps);
    }

    /** Distinct pairs and the first pair of the numerator and of the denominator week. */
    public static UniConstraintCollector<LessonUnit, ?, WeekBalance> weekBalance() {
        return new LaneCollector<>(LessonUnit.WEEK_TYPES, LessonUnit::weekLanes, counts -> new WeekBalance(
                counts.distinctPairs(LessonUnit.NUMERATOR_WEEK), counts.firstPair(LessonUnit.NUMERATOR_WEEK),
                counts.distinctPairs(LessonUnit.DENOMINATOR_WEEK), counts.firstPair(LessonUnit.DENOMINATOR_WEEK)));
    }

    private static final class LaneCollector<R> implements UniConstraintCollector<LessonUnit, LaneCounts, R> {

        private final int lanes;
        private final Function<LessonUnit, int[]> lanesOfUnit;
        private final Function<LaneCounts, R> result;

        private LaneCollector(int lanes, Function<LessonUnit, int[]> lanesOfUnit, Function<LaneCounts, R> result) {
            this.lanes = lanes;
            this.lanesOfUnit = lanesOfUnit;
            this.result = result;
        }

        @Override
        public Supplier<LaneCounts> supplier() {
            return () -> new LaneCounts(lanes);
        }

        @Override
        public BiFunction<LaneCounts, LessonUnit, Runnable> accumulator() {
            return (counts, unit) -> {
                // значення фіксуємо зараз: на момент відкату заняття вже може стояти деінде
                int pair = unit.getPair();
                int[] unitLanes = lanesOfUnit.apply(unit);
                counts.add(unitLanes, pair);
                return () -> counts.remove(unitLanes, pair);
            };
        }

        @Override
        public Function<LaneCounts, R> finisher() {
            return result;
        }
    }
}
