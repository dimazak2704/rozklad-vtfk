package ua.vtc.rozklad.scheduler.solver;

import java.util.List;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.ConstraintScore;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableAnalyzer;

/** Converts a solved timetable into the payload of {@code generation.completed}. */
public final class TimetableResults {

    private TimetableResults() {
    }

    /** Placement of every unit in snapshot order; a pinned unit without a room keeps {@code roomId = null}. */
    public static List<Assignment> assignments(Timetable timetable) {
        if (!timetable.isFullyPlaced()) {
            throw new IllegalStateException("the timetable is not fully placed");
        }
        return timetable.getUnits().stream().map(TimetableResults::assignment).toList();
    }

    private static Assignment assignment(LessonUnit unit) {
        return new Assignment(unit.getId(), unit.getTimeslot().weekday(), unit.getTimeslot().pair(), unit.getParity(),
                unit.getRoom().isNone() ? null : unit.getRoom().getId());
    }

    /**
     * Score per constraint with its Ukrainian title, hard constraints first; a constraint switched
     * off by a zero weight is listed with its matches and a zero score.
     */
    public static List<ConstraintScore> analysis(Timetable timetable) {
        return TimetableAnalyzer.analyze(timetable).stream()
                .map(result -> new ConstraintScore(result.constraint().title(), result.matchCount(),
                        result.score().toString()))
                .toList();
    }
}
