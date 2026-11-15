package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.DENOMINATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.NUMERATOR_WEEK;
import static ua.vtc.rozklad.scheduler.solver.domain.LessonUnit.WEEK_TYPES;

import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Teacher;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;

/** Teaching days of one teacher in the numerator and in the denominator week. */
public record TeacherWeek(int numeratorDays, int denominatorDays) {

    /** Days above the teacher's baseline in both kinds of week. */
    public int extraDays(Teacher teacher) {
        return teacher.extraDays(NUMERATOR_WEEK, numeratorDays) + teacher.extraDays(DENOMINATOR_WEEK, denominatorDays);
    }

    /** Incremental state of one teacher's week; every add returns its undo. */
    static final class Counts {

        private final int[][] counts = new int[WEEK_TYPES][Timeslot.MAX_WEEKDAY + 1];
        private final int[] distinctDays = new int[WEEK_TYPES];

        Runnable add(LessonUnit unit) {
            int weekday = unit.getWeekday();
            int[] weekLanes = unit.weekLanes();
            for (int week : weekLanes) {
                if (counts[week][weekday]++ == 0) {
                    distinctDays[week]++;
                }
            }
            return () -> {
                for (int week : weekLanes) {
                    if (--counts[week][weekday] == 0) {
                        distinctDays[week]--;
                    }
                }
            };
        }

        TeacherWeek result() {
            return new TeacherWeek(distinctDays[NUMERATOR_WEEK], distinctDays[DENOMINATOR_WEEK]);
        }
    }
}
