package ua.vtc.rozklad.scheduler.solver;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolutionUpdatePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.BaseScore;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * The solver's score of the timetable a run starts from: the base version's lessons where the warm start put them,
 * scored by the same constraints, weights ({@link Timetable#getConstraintWeightOverrides()}) and settings (Friday
 * limit, daily limits) as the run itself, before solving. Core's editor conflict check has fewer hard rules (no
 * Friday limit, group windows are soft there), so only this score tells how many hard violations the base had.
 *
 * <p>Units without a starting place (new load, a base slot outside the unit's range) are skipped by the
 * constraints, so they count for nothing: the score is that of the lessons the base really had.
 */
public final class StartingScore {

    private static final Logger log = LoggerFactory.getLogger(StartingScore.class);

    private StartingScore() {
    }

    /**
     * The starting score, or {@code null} when nothing is placed at the start (a run from scratch) or the score could
     * not be calculated (logged; the run goes on without it). The problem is left as it was: its score stays unset.
     */
    public static BaseScore of(SolutionManager<Timetable, HardSoftScore> scores, Timetable problem) {
        if (problem.getUnits().stream().allMatch(unit -> unit.getTimeslot() == null)) {
            return null;
        }
        HardSoftScore previous = problem.getScore();
        try {
            HardSoftScore score = scores.update(problem, SolutionUpdatePolicy.UPDATE_SCORE_ONLY);
            return new BaseScore(score.hardScore(), score.softScore());
        } catch (RuntimeException e) {
            log.warn("The starting timetable could not be scored, the run reports no base score", e);
            return null;
        } finally {
            problem.setScore(previous);
        }
    }
}
