package ua.vtc.rozklad.scheduler.solver.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import ai.timefold.solver.core.api.solver.SolutionManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.scheduler.solver.TimetableProblemFactory;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.support.Placements;
import ua.vtc.rozklad.scheduler.support.SyntheticCollege;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

/** The analysis must agree with the solver constraint by constraint, also for messy timetables. */
class TimetableAnalyzerTest {

    private static final Map<TimetableConstraint, BiFunction<TimetableConstraintProvider, ConstraintFactory, Constraint>>
            CONSTRAINTS = new EnumMap<>(TimetableConstraint.class);

    private static SolutionManager<Timetable, HardSoftScore> solutionManager;

    private final ConstraintVerifier<TimetableConstraintProvider, Timetable> verifier =
            ConstraintVerifier.build(new TimetableConstraintProvider(), Timetable.class, LessonUnit.class);

    @BeforeAll
    static void setUp() {
        solutionManager = TestSolvers.solutionManager();
        CONSTRAINTS.put(TimetableConstraint.TEACHER_CONFLICT, TimetableConstraintProvider::teacherConflict);
        CONSTRAINTS.put(TimetableConstraint.COHORT_CONFLICT, TimetableConstraintProvider::cohortConflict);
        CONSTRAINTS.put(TimetableConstraint.ROOM_CONFLICT, TimetableConstraintProvider::roomConflict);
        CONSTRAINTS.put(TimetableConstraint.ROOM_TYPE, TimetableConstraintProvider::roomType);
        CONSTRAINTS.put(TimetableConstraint.ROOM_CAPACITY, TimetableConstraintProvider::roomCapacity);
        CONSTRAINTS.put(TimetableConstraint.OUTSIDE_SHIFT, TimetableConstraintProvider::outsideShift);
        CONSTRAINTS.put(TimetableConstraint.TEACHER_UNAVAILABLE, TimetableConstraintProvider::teacherUnavailable);
        CONSTRAINTS.put(TimetableConstraint.GROUP_WINDOWS, TimetableConstraintProvider::groupWindows);
        CONSTRAINTS.put(TimetableConstraint.TEACHER_WINDOWS, TimetableConstraintProvider::teacherWindows);
        CONSTRAINTS.put(TimetableConstraint.BROKEN_DAY, TimetableConstraintProvider::brokenDays);
        CONSTRAINTS.put(TimetableConstraint.LATE_PAIR, TimetableConstraintProvider::latePairs);
        CONSTRAINTS.put(TimetableConstraint.DISCIPLINE_REPEAT, TimetableConstraintProvider::disciplineRepeat);
        CONSTRAINTS.put(TimetableConstraint.UNWANTED_SLOT, TimetableConstraintProvider::unwantedSlots);
        CONSTRAINTS.put(TimetableConstraint.ROOM_PREFERENCE, TimetableConstraintProvider::roomPreference);
        CONSTRAINTS.put(TimetableConstraint.HALF_PAIRING, TimetableConstraintProvider::halfPairing);
    }

    @Test
    void plantedTimetableIsFeasibleAndAnalysedLikeTheSolverScoresIt() {
        Timetable planted = planted(new GenerationWeights(10, 10, 3, 5, 1));

        List<TimetableAnalyzer.ConstraintResult> results = TimetableAnalyzer.analyze(planted);

        HardSoftScore solverScore = solutionManager.update(planted);
        assertThat(solverScore.hardScore()).isZero();
        assertThat(TimetableAnalyzer.totalScore(results)).isEqualTo(solverScore);
        assertImpactsMatchTheSolver(planted, results);
    }

    @Test
    void messyTimetableWithEveryKindOfViolationIsAnalysedLikeTheSolverScoresIt() {
        Timetable messy = perturb(planted(new GenerationWeights(7, 3, 2, 4, 6)), 0.2);

        List<TimetableAnalyzer.ConstraintResult> results = TimetableAnalyzer.analyze(messy);

        assertThat(results).filteredOn(result -> result.constraint().isHard())
                .allSatisfy(result -> assertThat(result.matchCount()).isPositive());
        assertThat(TimetableAnalyzer.totalScore(results)).isEqualTo(solutionManager.update(messy));
        assertImpactsMatchTheSolver(messy, results);
    }

    @Test
    void zeroWeightKeepsMatchesButScoresNothing() {
        Timetable messy = perturb(planted(new GenerationWeights(0, 10, 3, 5, 1)), 0.2);

        TimetableAnalyzer.ConstraintResult windows = TimetableAnalyzer.analyze(messy).stream()
                .filter(result -> result.constraint() == TimetableConstraint.GROUP_WINDOWS)
                .findFirst().orElseThrow();

        assertThat(windows.matchCount()).isPositive();
        assertThat(windows.score()).isEqualTo(HardSoftScore.ZERO);
    }

    @Test
    void unplacedTimetableIsRejected() {
        Timetable problem = new TimetableProblemFactory(5).create(
                SyntheticCollege.generate(SyntheticCollege.Options.medium()).snapshot(), GenerationWeights.DEFAULT);

        assertThatThrownBy(() -> TimetableAnalyzer.analyze(problem)).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertImpactsMatchTheSolver(Timetable timetable, List<TimetableAnalyzer.ConstraintResult> results) {
        // вплив не залежить від ваг, а перевірка одного обмеження не приймає ваги інших
        timetable.setConstraintWeightOverrides(ConstraintWeightOverrides.none());
        for (TimetableAnalyzer.ConstraintResult result : results) {
            Number solverImpact = verifier.verifyThat(CONSTRAINTS.get(result.constraint()))
                    .givenSolution(timetable).getImpact();
            assertThat(Math.abs(solverImpact.longValue()))
                    .as("impact of %s", result.constraint())
                    .isEqualTo(result.impact());
        }
    }

    private static Timetable planted(GenerationWeights weights) {
        SyntheticCollege.Problem problem = SyntheticCollege.generate(SyntheticCollege.Options.medium());
        Timetable timetable = new TimetableProblemFactory(5).create(problem.snapshot(), weights);
        return Placements.apply(timetable, problem.planted());
    }

    /**
     * Moves a share of the units to random slots, rooms and parities. The solver checks values
     * against value ranges, so the copies get the widest ranges (any slot, room and parity).
     */
    private static Timetable perturb(Timetable timetable, double share) {
        Random random = new Random(11);
        List<Timeslot> timeslots = timetable.getTimeslots();
        List<Room> rooms = timetable.getRooms();
        List<LessonUnit> units = new ArrayList<>();
        for (LessonUnit unit : timetable.getUnits()) {
            boolean moved = random.nextDouble() < share;
            Timeslot slot = moved ? timeslots.get(random.nextInt(timeslots.size())) : unit.getTimeslot();
            Room room = moved ? rooms.get(random.nextInt(rooms.size())) : unit.getRoom();
            Parity parity = moved && unit.isHalf()
                    ? (random.nextBoolean() ? Parity.NUMERATOR : Parity.DENOMINATOR)
                    : unit.getParity();
            units.add(LessonUnit.builder(unit.getId(), unit.getGroup(), unit.getDisciplineId())
                    .loadItemId(unit.getLoadItemId())
                    .subgroup(unit.getSubgroup())
                    .teacher(unit.getTeacher())
                    .weight(unit.getWeight())
                    .requiredRoomType(unit.getRequiredRoomType())
                    .desiredRoom(unit.getDesiredRoom())
                    .timeslotRange(timeslots)
                    .roomRange(rooms)
                    .parityRange(List.of(Parity.values()))
                    .placedAt(slot, room, parity)
                    .build());
        }
        return new Timetable(timeslots, rooms, timetable.getTeachers(), timetable.getGroups(), units,
                timetable.getConstraintWeightOverrides());
    }
}
