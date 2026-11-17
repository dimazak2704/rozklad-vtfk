package ua.vtc.rozklad.scheduler.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.BaseScore;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableAnalyzer;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.FridayBase;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

class StartingScoreTest {

    private static SolutionManager<Timetable, HardSoftScore> scores;

    private final TimetableProblemFactory factory = new TimetableProblemFactory(4);

    @BeforeAll
    static void solutionManager() {
        scores = TestSolvers.solutionManager();
    }

    /**
     * The 4th Friday pair is a hard violation for the solver (Friday limit 3), which core's conflict check does not
     * know: the base score says so before solving, once per kind of week, like the solver's own analysis.
     */
    @Test
    void fourthFridayPairOfTheBaseIsAHardViolation() {
        Timetable problem = factory.create(FridayBase.snapshot(0), GenerationWeights.DEFAULT);

        BaseScore base = StartingScore.of(scores, problem);

        assertThat(base).isNotNull();
        assertThat(base.hardScore()).as("one pair over the limit in the numerator and in the denominator week")
                .isEqualTo(-2);
        TimetableAnalyzer.ConstraintResult friday = TimetableAnalyzer.analyze(problem).stream()
                .filter(row -> row.constraint() == TimetableConstraint.FRIDAY_OVERLOAD).findFirst().orElseThrow();
        assertThat(base.hardScore()).isEqualTo(friday.score().hardScore())
                .isEqualTo(TimetableAnalyzer.totalScore(TimetableAnalyzer.analyze(problem)).hardScore());
        assertThat(base.softScore()).isEqualTo(TimetableAnalyzer.totalScore(TimetableAnalyzer.analyze(problem))
                .softScore());
        assertThat(problem.getScore()).as("the solver scores the problem itself").isNull();
    }

    /** New load has no base place: the base is scored without it instead of failing or counting it. */
    @Test
    void unitsWithoutAStartingPlaceCountForNothing() {
        Timetable withNewLoad = factory.create(FridayBase.snapshot(2), GenerationWeights.DEFAULT);
        Timetable baseOnly = factory.create(FridayBase.snapshot(0), GenerationWeights.DEFAULT);

        assertThat(withNewLoad.getUnits()).filteredOn(unit -> unit.getTimeslot() == null).hasSize(2);
        assertThat(StartingScore.of(scores, withNewLoad)).isEqualTo(StartingScore.of(scores, baseOnly));
    }

    @Test
    void runFromScratchHasNoBaseScore() {
        Timetable problem = factory.create(FridayBase.fromScratch(), GenerationWeights.DEFAULT);

        assertThat(problem.getUnits()).extracting(LessonUnit::getTimeslot).containsOnlyNulls();
        assertThat(StartingScore.of(scores, problem)).isNull();
    }

    @Test
    void scoreThatCannotBeCalculatedIsReportedAsUnknown() {
        @SuppressWarnings("unchecked")
        SolutionManager<Timetable, HardSoftScore> broken = mock(SolutionManager.class);
        when(broken.update(any(), any())).thenThrow(new IllegalStateException("broken constraint"));
        Timetable problem = factory.create(FridayBase.snapshot(0), GenerationWeights.DEFAULT);

        assertThat(StartingScore.of(broken, problem)).isNull();
        assertThat(problem.getScore()).isNull();
    }
}
