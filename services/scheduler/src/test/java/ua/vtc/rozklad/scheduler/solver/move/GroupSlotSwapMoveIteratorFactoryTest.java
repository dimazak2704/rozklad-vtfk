package ua.vtc.rozklad.scheduler.solver.move;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.heuristic.selector.move.factory.MoveIteratorFactoryConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningSolutionMetaModel;
import ai.timefold.solver.core.preview.api.move.test.MoveTestContext;
import ai.timefold.solver.core.preview.api.move.test.MoveTester;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.scheduler.solver.TimetableProblemFactory;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.ShiftWindow;
import ua.vtc.rozklad.scheduler.solver.domain.StudentGroup;
import ua.vtc.rozklad.scheduler.solver.domain.Teacher;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableAnalyzer;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.SyntheticCollege;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

/** Group slot swaps: the lessons of a group at two slots change places as columns (group windows are hard). */
class GroupSlotSwapMoveIteratorFactoryTest {

    private static final ShiftWindow FIRST_SHIFT = new ShiftWindow(List.of(new ShiftWindow.DayRange(1, 1, 4),
            new ShiftWindow.DayRange(2, 1, 4)));
    private static final StudentGroup KI2 = new StudentGroup(UUID.randomUUID(), "1КІ2", 28, 1, List.of(1, 2),
            FIRST_SHIFT);
    private static final StudentGroup KI1 = new StudentGroup(UUID.randomUUID(), "4КІ1", 25, 1, List.of(), FIRST_SHIFT);
    private static final Teacher KRENTSIN = new Teacher(UUID.randomUUID(), "Кренцін М.Д.", Set.of(), Set.of());
    private static final Teacher NEPYIVODA = new Teacher(UUID.randomUUID(), "Непийвода М.В.", Set.of(), Set.of());
    private static final Room LAB = new Room(UUID.randomUUID(), "6", RoomType.COMPUTER_LAB, 30, 1);
    private static final Room LAB2 = new Room(UUID.randomUUID(), "7", RoomType.COMPUTER_LAB, 30, 1);
    private static final List<Timeslot> RANGE = List.of(slot(1, 1), slot(1, 2), slot(1, 3), slot(1, 4), slot(2, 1),
            slot(2, 2), slot(2, 3), slot(2, 4));

    /**
     * Monday: pair 1 whole group, pair 2 two parallel subgroups, pair 3 whole group. Swapping pair 2 with Tuesday's pair
     * 1 moves both subgroups as one column and brings the Tuesday lesson to Monday: no day gets a window.
     */
    @Test
    void theGroupsLessonsAtTwoSlotsChangePlacesAsColumns() {
        LessonUnit mon1 = unit("mon1", KI2, 0, KRENTSIN, slot(1, 1), Parity.BOTH, LAB);
        LessonUnit sub1 = unit("sub1", KI2, 1, KRENTSIN, slot(1, 2), Parity.BOTH, LAB);
        LessonUnit sub2 = unit("sub2", KI2, 2, NEPYIVODA, slot(1, 2), Parity.BOTH, LAB2);
        LessonUnit mon3 = unit("mon3", KI2, 0, NEPYIVODA, slot(1, 3), Parity.BOTH, LAB);
        LessonUnit tue1 = unit("tue1", KI2, 0, KRENTSIN, slot(2, 1), Parity.BOTH, LAB);
        LessonUnit other = unit("other", KI1, 0, NEPYIVODA, slot(1, 2), Parity.BOTH, LAB);
        Timetable timetable = timetable(List.of(mon1, sub1, sub2, mon3, tue1, other));
        GroupSlotSwapMoveIteratorFactory factory = factoryFor(timetable);
        assertThat(TimetableAnalyzer.analyze(timetable)).filteredOn(row -> row.constraint() == TimetableConstraint.GROUP_WINDOWS)
                .singleElement().satisfies(row -> assertThat(row.matchCount()).isZero());

        SiblingChangeMove move = factory.swap(sub1, slot(2, 1));

        assertThat(move.units()).containsExactly(sub1, sub2, tue1);
        assertThat(move.slots()).containsExactly(slot(2, 1), slot(2, 1), slot(1, 2));
        assertThat(move.parities()).containsOnly(Parity.BOTH);
        MoveTestContext<Timetable> context = MoveTester.build(PlanningSolutionMetaModel.of(Timetable.class,
                LessonUnit.class)).using(timetable);
        context.executeTemporarily(move, view -> {
            assertThat(List.of(sub1.getTimeslot(), sub2.getTimeslot(), tue1.getTimeslot()))
                    .containsExactly(slot(2, 1), slot(2, 1), slot(1, 2));
            assertThat(TimetableAnalyzer.analyze(timetable))
                    .filteredOn(row -> row.constraint() == TimetableConstraint.GROUP_WINDOWS
                            || row.constraint() == TimetableConstraint.COHORT_CONFLICT)
                    .allSatisfy(row -> assertThat(row.matchCount()).as(row.constraint().name()).isZero());
        });
        assertThat(List.of(sub1.getTimeslot(), tue1.getTimeslot())).as("undone").containsExactly(slot(1, 2), slot(2, 1));
        assertThat(other.getTimeslot()).as("another group's lesson stays").isEqualTo(slot(1, 2));
    }

    /** A free slot of the group takes the whole column: both halves of a numerator/denominator slot move together. */
    @Test
    void aFreeSlotTakesTheWholeColumnWithItsWeeks() {
        LessonUnit numerator = unit("n", KI1, 0, KRENTSIN, slot(1, 1), Parity.NUMERATOR, LAB);
        LessonUnit denominator = unit("d", KI1, 0, NEPYIVODA, slot(1, 1), Parity.DENOMINATOR, LAB);
        LessonUnit second = unit("p2", KI1, 0, NEPYIVODA, slot(1, 2), Parity.BOTH, LAB);
        GroupSlotSwapMoveIteratorFactory factory = factoryFor(timetable(List.of(numerator, denominator, second)));

        SiblingChangeMove move = factory.swap(denominator, slot(1, 3));

        assertThat(move.units()).containsExactly(numerator, denominator);
        assertThat(move.slots()).containsOnly(slot(1, 3));
        assertThat(move.parities()).containsExactly(Parity.NUMERATOR, Parity.DENOMINATOR);
    }

    /**
     * Own lessons only: subgroup 2's copy of a parallel pair stands apart; swapping just subgroup 2's lessons of the two
     * slots puts it next to subgroup 1's copy and leaves subgroup 1 where it is. A whole-group lesson at a slot gives no
     * such move.
     */
    @Test
    void aSubgroupSwapsOnlyItsOwnLessons() {
        LessonUnit copy1 = unit("copy1", KI2, 1, KRENTSIN, slot(1, 1), Parity.BOTH, LAB);
        LessonUnit other2 = unit("other2", KI2, 2, KRENTSIN, slot(1, 1), Parity.BOTH, LAB2);
        LessonUnit other1 = unit("other1", KI2, 1, KRENTSIN, slot(1, 2), Parity.BOTH, LAB);
        LessonUnit copy2 = unit("copy2", KI2, 2, NEPYIVODA, slot(1, 2), Parity.BOTH, LAB2);
        LessonUnit whole = unit("whole", KI2, 0, NEPYIVODA, slot(1, 3), Parity.BOTH, LAB);
        GroupSlotSwapMoveIteratorFactory factory = factoryFor(timetable(List.of(copy1, other2, other1, copy2, whole)));

        SiblingChangeMove move = factory.swap(copy2, slot(1, 1), true);

        assertThat(move.units()).containsExactly(other2, copy2);
        assertThat(move.slots()).containsExactly(slot(1, 2), slot(1, 1));
        assertThat(factory.swap(copy2, slot(1, 3), true)).as("subgroup 2 sits in a whole-group lesson there").isNull();
        assertThat(factory.swap(copy2, slot(1, 1), false).units()).as("the whole columns")
                .containsExactly(copy1, other2, other1, copy2);
    }

    @Test
    void aPinnedLessonOrATargetOutsideARangeGivesNoMove() {
        LessonUnit free = unit("free", KI1, 0, KRENTSIN, slot(1, 1), Parity.BOTH, LAB);
        LessonUnit pinned = LessonUnit.builder("pinned", KI1, UUID.randomUUID()).teacher(NEPYIVODA)
                .weight(UnitWeight.WHOLE).pinnedAt(slot(1, 2), LAB, Parity.BOTH).build();
        LessonUnit mondayOnly = LessonUnit.builder("monday", KI1, UUID.randomUUID()).teacher(NEPYIVODA)
                .weight(UnitWeight.WHOLE).timeslotRange(RANGE.subList(0, 4)).roomRange(List.of(LAB))
                .parityRange(List.of(Parity.BOTH)).placedAt(slot(1, 3), LAB, Parity.BOTH).build();
        GroupSlotSwapMoveIteratorFactory factory = factoryFor(timetable(List.of(free, pinned, mondayOnly)));

        assertThat(factory.swap(free, slot(1, 2))).as("pinned at the target").isNull();
        assertThat(factory.swap(mondayOnly, slot(2, 1))).as("Tuesday is outside its range").isNull();
        assertThat(factory.swap(free, slot(2, 2))).as("a free Tuesday slot").isNotNull();
    }

    /** Local search with group slot swaps only, in FULL_ASSERT: the move keeps the score consistent. */
    @Test
    void groupSlotSwapsKeepTheIncrementalScoreConsistent() {
        SyntheticCollege.Problem problem = SyntheticCollege.generate(SyntheticCollege.Options.tiny());
        Timetable timetable = new TimetableProblemFactory(5).create(problem.snapshot(), GenerationWeights.DEFAULT);
        SolverConfig config = TestSolvers.config(TestSolvers.CONFIG,
                new TerminationConfig().withSpentLimit(Duration.ofMinutes(2)), EnvironmentMode.FULL_ASSERT);
        LocalSearchPhaseConfig swapsOnly = (LocalSearchPhaseConfig) config.getPhaseConfigList().getLast();
        swapsOnly.setMoveSelectorConfig(new MoveIteratorFactoryConfig()
                .withMoveIteratorFactoryClass(GroupSlotSwapMoveIteratorFactory.class));
        swapsOnly.setTerminationConfig(new TerminationConfig().withMoveCountLimit(400L));

        Timetable solution = SolverFactory.<Timetable>create(config).buildSolver().solve(timetable);

        assertThat(solution.isFullyPlaced()).isTrue();
    }

    private static GroupSlotSwapMoveIteratorFactory factoryFor(Timetable timetable) {
        @SuppressWarnings("unchecked")
        ScoreDirector<Timetable> director = mock(ScoreDirector.class);
        when(director.getWorkingSolution()).thenReturn(timetable);
        GroupSlotSwapMoveIteratorFactory factory = new GroupSlotSwapMoveIteratorFactory();
        factory.phaseStarted(director);
        return factory;
    }

    private static Timetable timetable(List<LessonUnit> units) {
        return new Timetable(RANGE, List.of(LAB, LAB2), List.of(KRENTSIN, NEPYIVODA), List.of(KI1, KI2),
                new ArrayList<>(units), null);
    }

    private static LessonUnit unit(String id, StudentGroup group, int subgroup, Teacher teacher, Timeslot slot,
                                   Parity parity, Room room) {
        return LessonUnit.builder(id, group, UUID.randomUUID()).subgroup(subgroup).teacher(teacher)
                .weight(parity == Parity.BOTH ? UnitWeight.WHOLE : UnitWeight.HALF).timeslotRange(RANGE)
                .roomRange(List.of(LAB, LAB2))
                .parityRange(parity == Parity.BOTH ? List.of(Parity.BOTH) : List.of(Parity.NUMERATOR, Parity.DENOMINATOR))
                .placedAt(slot, room, parity).build();
    }

    private static Timeslot slot(int weekday, int pair) {
        return new Timeslot(weekday, pair);
    }
}
