package ua.vtc.rozklad.scheduler.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableAnalyzer;
import ua.vtc.rozklad.scheduler.support.SyntheticCollege;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

/** Solving with the production solver configuration. */
class TimetableSolverTest {

    private final TimetableProblemFactory factory = new TimetableProblemFactory(5);

    @Test
    void realisticCollegeIsSolvedWithoutHardViolationsWithinTheLimit() {
        // 24 групи по 16 одиниць, дві зміни, підгрупи, спортзал на 3 групи одночасно
        SyntheticCollege.Problem problem = SyntheticCollege.generate(SyntheticCollege.Options.medium());
        Timetable timetable = factory.create(problem.snapshot(), GenerationWeights.DEFAULT);
        assertThat(timetable.getGroups()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(timetable.getUnits()).hasSize(24 * 16);

        TestSolvers.TimedResult result = TestSolvers.solveTimed(TestSolvers.factory(
                new TerminationConfig().withSpentLimit(Duration.ofSeconds(30))
                        .withUnimprovedSpentLimit(Duration.ofSeconds(5))), timetable);

        Timetable solution = result.solution();
        assertThat(solution.isFullyPlaced()).isTrue();
        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(result.firstFeasibleAt()).isNotNull().isLessThan(Duration.ofSeconds(30));
        assertThat(solution.getUnits()).allSatisfy(unit -> {
            assertThat(unit.getTimeslotRange()).contains(unit.getTimeslot());
            assertThat(unit.getRoomRange()).contains(unit.getRoom());
            assertThat(unit.getParityRange()).contains(unit.getParity());
        });
        assertThat(TimetableAnalyzer.totalScore(TimetableAnalyzer.analyze(solution))).isEqualTo(solution.getScore());
    }

    @Test
    void pinnedUnitsStayWhereTheDispatcherPutThem() {
        SyntheticCollege.Problem problem = SyntheticCollege.generate(SyntheticCollege.Options.small());
        List<ProblemSnapshot.Unit> units = new ArrayList<>(problem.snapshot().units());
        Map<String, Assignment> planted = problem.planted().stream()
                .collect(Collectors.toMap(Assignment::unitId, Function.identity()));
        List<String> pinnedIds = new ArrayList<>();
        for (int i = 0; i < units.size(); i += 4) {
            ProblemSnapshot.Unit unit = units.get(i);
            Assignment place = planted.get(unit.unitId());
            units.set(i, pin(unit, new ProblemSnapshot.PinnedSlot(place.weekday(), place.pair(), place.parity(),
                    i % 8 == 0 ? null : place.roomId())));
            pinnedIds.add(unit.unitId());
        }
        ProblemSnapshot snapshot = withUnits(problem.snapshot(), units);

        Timetable solution = TestSolvers.solveTimed(TestSolvers.factory(
                new TerminationConfig().withSpentLimit(Duration.ofSeconds(5))),
                factory.create(snapshot, GenerationWeights.DEFAULT)).solution();

        Map<String, Assignment> result = TimetableResults.assignments(solution).stream()
                .collect(Collectors.toMap(Assignment::unitId, Function.identity()));
        for (int i = 0; i < pinnedIds.size(); i++) {
            String unitId = pinnedIds.get(i);
            Assignment expected = planted.get(unitId);
            Assignment actual = result.get(unitId);
            assertThat(actual.weekday()).isEqualTo(expected.weekday());
            assertThat(actual.pair()).isEqualTo(expected.pair());
            assertThat(actual.parity()).isEqualTo(expected.parity());
            assertThat(actual.roomId()).isEqualTo(i % 2 == 0 ? null : expected.roomId());
        }
        assertThat(solution.getScore().hardScore()).isZero();
    }

    @Test
    void problemWithOnlyPinnedUnitsIsReturnedAsGiven() {
        SyntheticCollege.Problem problem = SyntheticCollege.generate(SyntheticCollege.Options.small());
        Map<String, Assignment> planted = problem.planted().stream()
                .collect(Collectors.toMap(Assignment::unitId, Function.identity()));
        List<ProblemSnapshot.Unit> pinned = problem.snapshot().units().stream()
                .map(unit -> {
                    Assignment place = planted.get(unit.unitId());
                    return pin(unit, new ProblemSnapshot.PinnedSlot(place.weekday(), place.pair(), place.parity(),
                            place.roomId()));
                })
                .toList();

        Timetable solution = TestSolvers.solveTimed(TestSolvers.factory(
                        new TerminationConfig().withSpentLimit(Duration.ofSeconds(5))),
                factory.create(withUnits(problem.snapshot(), pinned), GenerationWeights.DEFAULT)).solution();

        assertThat(TimetableResults.assignments(solution)).containsExactlyElementsOf(problem.planted());
    }

    @Test
    void incrementalScoreMatchesAFullRecalculationAfterEveryMove() {
        // FULL_ASSERT перераховує рахунок з нуля після кожного ходу й падає при розбіжності
        SyntheticCollege.Problem problem = SyntheticCollege.generate(SyntheticCollege.Options.tiny());
        Timetable timetable = factory.create(problem.snapshot(), new GenerationWeights(3, 2, 1, 4, 1));
        // кількість кроків, а не час: на завантаженій машині евристика побудови теж має завершитися
        SolverConfig config = TestSolvers.config(TestSolvers.CONFIG,
                new TerminationConfig().withSpentLimit(Duration.ofMinutes(3)), EnvironmentMode.FULL_ASSERT);
        config.getPhaseConfigList().getLast().setTerminationConfig(new TerminationConfig().withStepCountLimit(100));

        Timetable solution = TestSolvers.solveTimed(SolverFactory.create(config), timetable).solution();

        assertThat(solution.isFullyPlaced()).as("construction finished, local search ran").isTrue();
    }

    private static ProblemSnapshot.Unit pin(ProblemSnapshot.Unit unit, ProblemSnapshot.PinnedSlot slot) {
        return new ProblemSnapshot.Unit(unit.unitId(), unit.loadItemId(), unit.groupId(), unit.subgroup(),
                unit.disciplineId(), unit.teacherId(), unit.weight(), unit.requiredRoomType(), unit.preferredRoomId(),
                unit.homeRoomId(), slot);
    }

    private static ProblemSnapshot withUnits(ProblemSnapshot snapshot, List<ProblemSnapshot.Unit> units) {
        return new ProblemSnapshot(snapshot.timeslots(), snapshot.shiftWindows(), snapshot.groups(), snapshot.rooms(),
                snapshot.teachers(), units);
    }
}
