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
import java.util.Random;
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
import ua.vtc.rozklad.scheduler.support.SyntheticCollege;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

class SiblingMoveIteratorFactoryTest {

    private static final ShiftWindow FIRST_SHIFT = new ShiftWindow(List.of(new ShiftWindow.DayRange(1, 1, 4),
            new ShiftWindow.DayRange(2, 1, 4)));
    private static final StudentGroup KI2 = new StudentGroup(UUID.randomUUID(), "1КІ2", 28, 1, List.of(1, 2),
            FIRST_SHIFT);
    private static final Teacher KRENTSIN = new Teacher(UUID.randomUUID(), "Кренцін М.Д.", Set.of(), Set.of());
    private static final Teacher NEPYIVODA = new Teacher(UUID.randomUUID(), "Непийвода М.В.", Set.of(), Set.of());
    private static final Room LAB = new Room(UUID.randomUUID(), "6", RoomType.COMPUTER_LAB, 16, 1);
    private static final UUID DISCIPLINE = UUID.randomUUID();

    @Test
    void groupOffersOnlySlotsAndParitiesEverySiblingMayTake() {
        LessonUnit first = unit("a", 1, KRENTSIN, "k", slots(1, 1, 1, 2, 2, 1), false);
        LessonUnit second = unit("b", 2, NEPYIVODA, "k", slots(1, 2, 2, 1, 2, 2), false);

        List<SiblingMoveIteratorFactory.SiblingGroup> groups =
                SiblingMoveIteratorFactory.siblingGroups(List.of(first, second));

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.slots()).containsExactly(new Timeslot(1, 2), new Timeslot(2, 1));
            assertThat(group.parities()).containsExactly(Parity.BOTH);
            assertThat(group.sharedTeacher()).isFalse();
        });
    }

    @Test
    void pinnedUnitsLoneKeysAndUnitsWithoutKeyMakeNoGroup() {
        List<SiblingMoveIteratorFactory.SiblingGroup> groups = SiblingMoveIteratorFactory.siblingGroups(List.of(
                unit("a", 1, KRENTSIN, "pinned", slots(1, 1), true),
                unit("b", 2, NEPYIVODA, "pinned", slots(1, 1), false),
                unit("c", 1, KRENTSIN, "alone", slots(1, 1), false),
                unit("d", 1, KRENTSIN, null, slots(1, 1), false),
                unit("e", 2, KRENTSIN, null, slots(1, 1), false)));

        assertThat(groups).isEmpty();
    }

    @Test
    void differentTeachersGoIntoOneSlotAndOneTeacherIntoNeighbouringPairs() {
        List<Timeslot> range = slots(1, 1, 1, 2, 1, 3, 1, 4);
        SiblingMoveIteratorFactory.SiblingGroup parallel = SiblingMoveIteratorFactory.siblingGroups(List.of(
                unit("a", 1, KRENTSIN, "k", range, false), unit("b", 2, NEPYIVODA, "k", range, false))).getFirst();
        SiblingMoveIteratorFactory.SiblingGroup oneTeacher = SiblingMoveIteratorFactory.siblingGroups(List.of(
                unit("c", 1, KRENTSIN, "t", range, false), unit("d", 2, KRENTSIN, "t", range, false))).getFirst();

        assertThat(parallel.moveTo(new Timeslot(1, 3), Parity.BOTH).getPlanningValues())
                .containsExactly(new Timeslot(1, 3), new Timeslot(1, 3), Parity.BOTH, Parity.BOTH);
        assertThat(oneTeacher.sharedTeacher()).isTrue();
        assertThat(oneTeacher.moveTo(new Timeslot(1, 3), Parity.BOTH).getPlanningValues())
                .containsExactly(new Timeslot(1, 3), new Timeslot(1, 4), Parity.BOTH, Parity.BOTH);
        assertThat(oneTeacher.moveTo(new Timeslot(1, 4), Parity.BOTH)).as("pair 5 is outside the shift").isNull();
    }

    /**
     * Repair finding: a group whose shared teacher has no two consecutive pairs in every unit's range offered slots
     * from which no move exists, and the endless random iterator then threw {@code NoSuchElementException}, which
     * ends the solve. Such start slots are dropped; a group with none offers nothing.
     */
    @Test
    void oneTeacherWithoutConsecutivePairsOffersNoMoveInsteadOfFailing() {
        List<Timeslot> gaps = slots(1, 1, 1, 3, 2, 2, 2, 4);
        LessonUnit first = unit("a", 1, KRENTSIN, "t", gaps, false);
        LessonUnit second = unit("b", 2, KRENTSIN, "t", gaps, false);
        Timetable timetable = new Timetable(gaps, List.of(LAB), List.of(KRENTSIN), List.of(KI2),
                List.of(first, second), null);
        List<Timeslot> range = slots(1, 1, 1, 2, 1, 3, 2, 4);
        SiblingMoveIteratorFactory.SiblingGroup partly = SiblingMoveIteratorFactory.siblingGroups(List.of(
                unit("c", 1, KRENTSIN, "p", range, false), unit("d", 2, KRENTSIN, "p", range, false))).getFirst();
        SiblingMoveIteratorFactory factory = new SiblingMoveIteratorFactory();
        @SuppressWarnings("unchecked")
        ScoreDirector<Timetable> director = mock(ScoreDirector.class);
        when(director.getWorkingSolution()).thenReturn(timetable);

        assertThat(SiblingMoveIteratorFactory.siblingGroups(timetable.getUnits())).isEmpty();
        assertThat(factory.createRandomMoveIterator(director, new Random(0)).hasNext()).isFalse();
        assertThat(factory.getSize(director)).isZero();
        assertThat(partly.slots()).as("only starts with a next pair in range")
                .containsExactly(new Timeslot(1, 1), new Timeslot(1, 2));
    }

    @Test
    void moveSetsEverySiblingAndIsUndoneCleanly() {
        List<Timeslot> range = slots(1, 1, 1, 2, 1, 3, 1, 4);
        LessonUnit first = unit("a", 1, KRENTSIN, "k", range, false);
        LessonUnit second = unit("b", 2, NEPYIVODA, "k", range, false);
        second.setTimeslot(range.get(3));
        Timetable timetable = new Timetable(range, List.of(LAB), List.of(KRENTSIN, NEPYIVODA), List.of(KI2),
                List.of(first, second), null);
        SiblingChangeMove move = SiblingMoveIteratorFactory.siblingGroups(timetable.getUnits()).getFirst()
                .moveTo(range.get(2), Parity.BOTH);
        MoveTestContext<Timetable> context = MoveTester.build(PlanningSolutionMetaModel.of(Timetable.class,
                LessonUnit.class)).using(timetable);

        context.executeTemporarily(move, view -> assertThat(List.of(first.getTimeslot(), second.getTimeslot()))
                .containsOnly(range.get(2)));
        assertThat(List.of(first.getTimeslot(), second.getTimeslot())).containsExactly(range.get(0), range.get(3));

        context.execute(move);
        assertThat(List.of(first.getTimeslot(), second.getTimeslot())).containsOnly(range.get(2));
        assertThat(move.getPlanningEntities()).containsExactly(first, second);
    }

    /** Local search with sibling moves only, in FULL_ASSERT: the move keeps the score consistent. */
    @Test
    void siblingMovesKeepTheIncrementalScoreConsistent() {
        SyntheticCollege.Problem problem = SyntheticCollege.generate(SyntheticCollege.Options.tiny());
        Timetable timetable = new TimetableProblemFactory(5).create(problem.snapshot(), GenerationWeights.DEFAULT);
        SolverConfig config = TestSolvers.config(TestSolvers.CONFIG,
                new TerminationConfig().withSpentLimit(Duration.ofMinutes(2)), EnvironmentMode.FULL_ASSERT);
        // фаза локального пошуку з продакшн-конфігурації, але лише з ходами підгруп
        LocalSearchPhaseConfig siblingsOnly = (LocalSearchPhaseConfig) config.getPhaseConfigList().getLast();
        siblingsOnly.setMoveSelectorConfig(new MoveIteratorFactoryConfig()
                .withMoveIteratorFactoryClass(SiblingMoveIteratorFactory.class));
        // кількість ходів, а не кроків: на локальному оптимумі ходи підгруп рідко приймаються, а FULL_ASSERT
        // перевіряє кожен оцінений хід і його відкат
        siblingsOnly.setTerminationConfig(new TerminationConfig().withMoveCountLimit(400L));

        Timetable solution = SolverFactory.<Timetable>create(config).buildSolver().solve(timetable);

        assertThat(solution.isFullyPlaced()).isTrue();
        List<LessonUnit> siblings = solution.getUnits().stream().filter(LessonUnit::hasSiblingKey).toList();
        assertThat(siblings).isNotEmpty();
    }

    private static LessonUnit unit(String id, int subgroup, Teacher teacher, String siblingKey, List<Timeslot> range,
                                   boolean pinned) {
        LessonUnit.Builder builder = LessonUnit.builder(id, KI2, DISCIPLINE)
                .subgroup(subgroup)
                .teacher(teacher)
                .weight(UnitWeight.WHOLE)
                .siblingKey(siblingKey);
        if (pinned) {
            return builder.pinnedAt(range.getFirst(), LAB, Parity.BOTH).build();
        }
        return builder.timeslotRange(range).roomRange(List.of(LAB)).parityRange(List.of(Parity.BOTH))
                .placedAt(range.getFirst(), LAB, Parity.BOTH).build();
    }

    private static List<Timeslot> slots(int... weekdayPair) {
        List<Timeslot> slots = new ArrayList<>();
        for (int i = 0; i + 1 < weekdayPair.length; i += 2) {
            slots.add(new Timeslot(weekdayPair[i], weekdayPair[i + 1]));
        }
        return slots;
    }
}
