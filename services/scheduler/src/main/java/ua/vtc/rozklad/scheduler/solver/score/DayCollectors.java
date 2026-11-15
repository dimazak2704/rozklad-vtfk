package ua.vtc.rozklad.scheduler.solver.score;

import ai.timefold.solver.core.api.score.stream.uni.UniConstraintCollector;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;

/**
 * Incremental collectors over the lessons of one day or week (grouped by group or teacher). Each
 * lesson adds its pair to the lanes it occupies; the collector undoes exactly that when the lesson
 * moves, so the values stay correct during local search. Every collector is a single shared instance,
 * so constraints that group the same way share one node (Bavet shares equal streams).
 */
public final class DayCollectors {

    private static final UniConstraintCollector<LessonUnit, ?, GroupDay> GROUP_DAY =
            new UnitCollector<>(GroupDay.Counts::new, GroupDay.Counts::add, GroupDay.Counts::result);
    private static final UniConstraintCollector<LessonUnit, ?, TeacherDay> TEACHER_DAY =
            new UnitCollector<>(TeacherDay.Counts::new, TeacherDay.Counts::add, TeacherDay.Counts::result);
    private static final UniConstraintCollector<LessonUnit, ?, TeacherWeek> TEACHER_WEEK =
            new UnitCollector<>(TeacherWeek.Counts::new, TeacherWeek.Counts::add, TeacherWeek.Counts::result);
    private static final UniConstraintCollector<LessonUnit, ?, DisciplineDay> DISCIPLINE_DAY =
            new UnitCollector<>(DisciplineDay.Counts::new, DisciplineDay.Counts::add, DisciplineDay.Counts::result);

    private DayCollectors() {
    }

    /** Windows (worst cohort), broken day, one-pair weeks and lone subgroup slots of a group-day. */
    public static UniConstraintCollector<LessonUnit, ?, GroupDay> groupDay() {
        return GROUP_DAY;
    }

    /** Pairs, windows and waits between shifts of a teacher-day. */
    public static UniConstraintCollector<LessonUnit, ?, TeacherDay> teacherDay() {
        return TEACHER_DAY;
    }

    /** Teaching days of a teacher per kind of week. */
    public static UniConstraintCollector<LessonUnit, ?, TeacherWeek> teacherWeek() {
        return TEACHER_WEEK;
    }

    /** Pairs of one discipline in a group-day, for the group and for its busiest cohort. */
    public static UniConstraintCollector<LessonUnit, ?, DisciplineDay> disciplineDay() {
        return DISCIPLINE_DAY;
    }

    /** A collector whose state undoes each lesson's contribution with the returned runnable. */
    private record UnitCollector<S, R>(Supplier<S> supplier, BiFunction<S, LessonUnit, Runnable> accumulator,
                                       Function<S, R> finisher) implements UniConstraintCollector<LessonUnit, S, R> {
    }
}
