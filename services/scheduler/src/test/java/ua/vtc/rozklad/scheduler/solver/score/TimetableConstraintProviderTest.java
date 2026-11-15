package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.contracts.Parity.BOTH;
import static ua.vtc.rozklad.contracts.Parity.DENOMINATOR;
import static ua.vtc.rozklad.contracts.Parity.NUMERATOR;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.TimetableProblemFactory;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.ShiftWindow;
import ua.vtc.rozklad.scheduler.solver.domain.StudentGroup;
import ua.vtc.rozklad.scheduler.solver.domain.Teacher;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

class TimetableConstraintProviderTest {

    private static final ShiftWindow FIRST_SHIFT = new ShiftWindow(List.of(
            new ShiftWindow.DayRange(1, 1, 4), new ShiftWindow.DayRange(2, 1, 4), new ShiftWindow.DayRange(3, 1, 4),
            new ShiftWindow.DayRange(4, 1, 4), new ShiftWindow.DayRange(5, 1, 4)));
    private static final ShiftWindow SECOND_SHIFT = new ShiftWindow(List.of(
            new ShiftWindow.DayRange(1, 5, 8), new ShiftWindow.DayRange(2, 5, 8), new ShiftWindow.DayRange(3, 5, 8),
            new ShiftWindow.DayRange(4, 5, 8), new ShiftWindow.DayRange(5, 4, 6)));

    private static final StudentGroup KI1 = new StudentGroup(UUID.randomUUID(), "4КІ1", 25, 1, List.of(), FIRST_SHIFT);
    private static final StudentGroup KI2 = new StudentGroup(UUID.randomUUID(), "1КІ2", 28, 1, List.of(1, 2),
            FIRST_SHIFT);
    private static final StudentGroup EA1 = new StudentGroup(UUID.randomUUID(), "2ЕА1", 30, 2, List.of(),
            SECOND_SHIFT);
    /** The college's Friday limit (setting «Максимум пар у пʼятницю», default 3). */
    private static final StudentGroup RT1 = new StudentGroup(UUID.randomUUID(), "4РТ1", 25, 1, List.of(), FIRST_SHIFT, 3);
    private static final StudentGroup M1 = new StudentGroup(UUID.randomUUID(), "1М", 28, 1, List.of(1, 2), FIRST_SHIFT,
            3);

    private static final Teacher KRENTSIN = new Teacher(UUID.randomUUID(), "Кренцін М.Д.",
            Set.of(slot(1, 1)), Set.of(slot(2, 1)));
    private static final Teacher NEPYIVODA = new Teacher(UUID.randomUUID(), "Непийвода М.В.", Set.of(), Set.of());
    /** Daily limit 2 pairs, baseline 2 teaching days in each kind of week. */
    private static final Teacher KOLESNYK = new Teacher(UUID.randomUUID(), "Колесник А.І.", Set.of(), Set.of(), 2,
            new int[] {2, 2});

    private static final Room ROOM_6 = new Room(UUID.randomUUID(), "6", RoomType.COMPUTER_LAB, 30, 1);
    private static final Room ROOM_304 = new Room(UUID.randomUUID(), "304", RoomType.CLASSROOM, 20, 1);
    private static final Room ROOM_UNKNOWN = new Room(UUID.randomUUID(), "12", RoomType.CLASSROOM, 0, 1);
    private static final Room GYM = new Room(UUID.randomUUID(), "с.з.", RoomType.GYM, 90, 3);

    private static final UUID STP = UUID.randomUUID();
    private static final UUID NDEKSM = UUID.randomUUID();

    private final ConstraintVerifier<TimetableConstraintProvider, Timetable> verifier =
            ConstraintVerifier.build(new TimetableConstraintProvider(), Timetable.class, LessonUnit.class);

    private int counter;

    @Nested
    class TeacherConflict {

        @Test
        void twoWeeklyLessonsOfOneTeacherAtOnceClashInBothWeeks() {
            verifier.verifyThat(TimetableConstraintProvider::teacherConflict)
                    .given(lesson(KI1, slot(1, 2), BOTH).teacher(KRENTSIN).build(),
                            lesson(KI2, slot(1, 2), BOTH).teacher(KRENTSIN).build())
                    .penalizesBy(2);
        }

        @Test
        void halfAgainstWeeklyLessonClashesInOneWeek() {
            verifier.verifyThat(TimetableConstraintProvider::teacherConflict)
                    .given(lesson(KI1, slot(1, 2), NUMERATOR).teacher(KRENTSIN).build(),
                            lesson(KI2, slot(1, 2), BOTH).teacher(KRENTSIN).build())
                    .penalizesBy(1);
        }

        @Test
        void numeratorAndDenominatorOfOneTeacherShareTheSlot() {
            verifier.verifyThat(TimetableConstraintProvider::teacherConflict)
                    .given(lesson(KI1, slot(1, 2), NUMERATOR).teacher(KRENTSIN).build(),
                            lesson(KI2, slot(1, 2), DENOMINATOR).teacher(KRENTSIN).build())
                    .hasNoImpact();
        }

        @Test
        void differentTeachersSlotsOrMissingTeachersDoNotClash() {
            verifier.verifyThat(TimetableConstraintProvider::teacherConflict)
                    .given(lesson(KI1, slot(1, 2), BOTH).teacher(KRENTSIN).build(),
                            lesson(KI2, slot(1, 2), BOTH).teacher(NEPYIVODA).build(),
                            lesson(EA1, slot(1, 3), BOTH).teacher(KRENTSIN).build(),
                            lesson(KI1, slot(2, 2), BOTH).build(),
                            lesson(KI2, slot(2, 2), BOTH).build())
                    .hasNoImpact();
        }

        @Test
        void threeLessonsAtOnceArePenalizedPerPair() {
            verifier.verifyThat(TimetableConstraintProvider::teacherConflict)
                    .given(lesson(KI1, slot(3, 1), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI2, slot(3, 1), BOTH).teacher(NEPYIVODA).build(),
                            lesson(EA1, slot(3, 1), BOTH).teacher(NEPYIVODA).build())
                    .penalizesBy(6);
        }
    }

    @Nested
    class CohortConflict {

        @Test
        void twoWholeGroupLessonsAtOnce() {
            verifier.verifyThat(TimetableConstraintProvider::cohortConflict)
                    .given(lesson(KI1, slot(1, 1), BOTH).build(), lesson(KI1, slot(1, 1), BOTH).build())
                    .penalizesBy(2);
        }

        @Test
        void wholeGroupOverlapsEverySubgroup() {
            verifier.verifyThat(TimetableConstraintProvider::cohortConflict)
                    .given(lesson(KI2, slot(1, 1), BOTH).build(),
                            lesson(KI2, slot(1, 1), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(1, 1), NUMERATOR).subgroup(2).build())
                    .penalizesBy(3);
        }

        @Test
        void differentSubgroupsMayStudyAtOnce() {
            verifier.verifyThat(TimetableConstraintProvider::cohortConflict)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(1, 1), BOTH).subgroup(2).build())
                    .hasNoImpact();
        }

        @Test
        void sameSubgroupTwiceClashes() {
            verifier.verifyThat(TimetableConstraintProvider::cohortConflict)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(1, 1), DENOMINATOR).subgroup(1).build())
                    .penalizesBy(1);
        }

        @Test
        void halvesOfTheSameSlotAndOtherGroupsDoNotClash() {
            verifier.verifyThat(TimetableConstraintProvider::cohortConflict)
                    .given(lesson(KI1, slot(1, 1), NUMERATOR).build(), lesson(KI1, slot(1, 1), DENOMINATOR).build(),
                            lesson(KI2, slot(1, 1), BOTH).build(), lesson(KI1, slot(1, 2), BOTH).build())
                    .hasNoImpact();
        }
    }

    @Nested
    class RoomConflict {

        @Test
        void twoLessonsInAnExclusiveRoom() {
            verifier.verifyThat(TimetableConstraintProvider::roomConflict)
                    .given(lesson(KI1, slot(1, 1), BOTH).room(ROOM_6).build(),
                            lesson(KI2, slot(1, 1), BOTH).room(ROOM_6).build())
                    .penalizesBy(2);
        }

        @Test
        void numeratorAndDenominatorShareARoom() {
            verifier.verifyThat(TimetableConstraintProvider::roomConflict)
                    .given(lesson(KI1, slot(1, 1), NUMERATOR).room(ROOM_6).build(),
                            lesson(KI2, slot(1, 1), DENOMINATOR).room(ROOM_6).build(),
                            lesson(EA1, slot(1, 2), BOTH).room(ROOM_6).build())
                    .hasNoImpact();
        }

        @Test
        void sportsHallHostsThreeGroupsButNotFour() {
            List<LessonUnit> three = List.of(lesson(KI1, slot(2, 3), BOTH).room(GYM).build(),
                    lesson(KI2, slot(2, 3), BOTH).room(GYM).build(), lesson(EA1, slot(2, 3), BOTH).room(GYM).build());
            verifier.verifyThat(TimetableConstraintProvider::roomConflict).given(three.toArray()).hasNoImpact();

            List<Object> four = new ArrayList<>(three);
            four.add(lesson(KI1, slot(2, 3), NUMERATOR).room(GYM).build());
            verifier.verifyThat(TimetableConstraintProvider::roomConflict).given(four.toArray()).penalizesBy(1);
        }

        @Test
        void pinnedLessonsWithoutRoomOccupyNothing() {
            verifier.verifyThat(TimetableConstraintProvider::roomConflict)
                    .given(lesson(KI1, slot(1, 1), BOTH).room(Room.NONE).build(),
                            lesson(KI2, slot(1, 1), BOTH).room(Room.NONE).build())
                    .hasNoImpact();
        }
    }

    @Nested
    class RoomTypeAndCapacity {

        @Test
        void requiredLabInAClassroom() {
            verifier.verifyThat(TimetableConstraintProvider::roomType)
                    .given(lesson(KI1, slot(1, 1), BOTH).requiredRoomType(RoomType.COMPUTER_LAB).room(ROOM_304).build(),
                            lesson(KI2, slot(1, 1), NUMERATOR).requiredRoomType(RoomType.GYM).room(ROOM_304).build())
                    .penalizesBy(3);
        }

        @Test
        void matchingTypeNoRequirementOrNoRoomAreFine() {
            verifier.verifyThat(TimetableConstraintProvider::roomType)
                    .given(lesson(KI1, slot(1, 1), BOTH).requiredRoomType(RoomType.COMPUTER_LAB).room(ROOM_6).build(),
                            lesson(KI2, slot(1, 1), BOTH).room(GYM).build(),
                            lesson(EA1, slot(1, 1), BOTH).requiredRoomType(RoomType.GYM).room(Room.NONE).build())
                    .hasNoImpact();
        }

        @Test
        void wholeGroupInASmallRoom() {
            verifier.verifyThat(TimetableConstraintProvider::roomCapacity)
                    .given(lesson(KI1, slot(1, 1), BOTH).room(ROOM_304).build())
                    .penalizesBy(2);
        }

        @Test
        void subgroupsAndUnknownCapacityAreNotChecked() {
            StudentGroup unknownSize = new StudentGroup(UUID.randomUUID(), "4ВЕ", 0, 1, List.of(), FIRST_SHIFT);
            verifier.verifyThat(TimetableConstraintProvider::roomCapacity)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).room(ROOM_304).build(),
                            lesson(KI1, slot(1, 2), BOTH).room(ROOM_UNKNOWN).build(),
                            lesson(unknownSize, slot(1, 3), BOTH).room(ROOM_304).build(),
                            lesson(KI1, slot(1, 4), BOTH).room(ROOM_6).build())
                    .hasNoImpact();
        }
    }

    @Nested
    class ShiftAndAvailability {

        @Test
        void firstShiftGroupAtTheFifthPair() {
            verifier.verifyThat(TimetableConstraintProvider::outsideShift)
                    .given(lesson(KI1, slot(1, 5), BOTH).build(), lesson(EA1, slot(5, 7), NUMERATOR).build(),
                            lesson(KI1, slot(1, 4), BOTH).build(), lesson(EA1, slot(5, 4), BOTH).build())
                    .penalizesBy(3);
        }

        @Test
        void teacherUnavailableSlot() {
            verifier.verifyThat(TimetableConstraintProvider::teacherUnavailable)
                    .given(lesson(KI1, slot(1, 1), BOTH).teacher(KRENTSIN).build(),
                            lesson(KI1, slot(1, 2), BOTH).teacher(KRENTSIN).build(),
                            lesson(KI2, slot(1, 1), BOTH).teacher(NEPYIVODA).build(),
                            lesson(EA1, slot(1, 1), BOTH).build())
                    .penalizesBy(2);
        }

        @Test
        void teacherUnwantedSlot() {
            verifier.verifyThat(TimetableConstraintProvider::unwantedSlots)
                    .given(lesson(KI1, slot(2, 1), NUMERATOR).teacher(KRENTSIN).build(),
                            lesson(KI1, slot(2, 2), BOTH).teacher(KRENTSIN).build(),
                            lesson(KI2, slot(2, 1), BOTH).teacher(NEPYIVODA).build())
                    .penalizesBy(1);
        }
    }

    /**
     * Group windows are a hard rule since the owner's answers, round 2 (15.11.2026): «вікна групам не варто лишати,
     * краще викладачам вікна». Teacher windows stay soft.
     */
    @Nested
    class Windows {

        @Test
        void freePairBetweenLessonsIsAWindowInBothWeeks() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI1, slot(1, 1), BOTH).build(), lesson(KI1, slot(1, 3), BOTH).build())
                    .penalizesBy(2);
        }

        @Test
        void aGroupWindowIsHardWhateverTheRequestWeights() {
            LessonUnit first = lesson(KI1, slot(1, 1), BOTH).build();
            LessonUnit third = lesson(KI1, slot(1, 3), BOTH).build();
            Timetable timetable = new Timetable(List.of(), List.of(ROOM_6), List.of(), List.of(KI1),
                    List.of(first, third), ConstraintWeights.of(new GenerationWeights(0, 0, 0, 0, 0), 0));

            // вага windows 0 вікна групи не вимикає: −2 hard; вікон викладача немає (без викладача)
            verifier.verifyThat().givenSolution(timetable).scores(HardSoftScore.ofHard(-2));
        }

        /** A day of lessons pinned outside the scope never makes the run infeasible. */
        @Test
        void aWindowBetweenPinnedLessonsOnlyIsExempt() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI1, slot(1, 1), BOTH).pinned().build(), lesson(KI1, slot(1, 3), BOTH).pinned().build(),
                            lesson(KI2, slot(1, 1), BOTH).pinned().build(), lesson(KI2, slot(1, 4), BOTH).pinned().build())
                    .hasNoImpact();
        }

        /** A free lesson on the day makes the day the run's: the window counts, the solver can fill it or leave. */
        @Test
        void aFreeLessonOnTheDayMakesThePinnedWindowCount() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI1, slot(1, 1), BOTH).pinned().build(), lesson(KI1, slot(1, 3), BOTH).build(),
                            lesson(KI1, slot(2, 1), BOTH).pinned().build(), lesson(KI1, slot(2, 3), BOTH).pinned().build())
                    .penalizesBy(2);
        }

        @Test
        void halfFillsTheWindowOnlyInItsWeek() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI1, slot(1, 1), BOTH).build(), lesson(KI1, slot(1, 2), NUMERATOR).build(),
                            lesson(KI1, slot(1, 3), BOTH).build())
                    .penalizesBy(1);
        }

        @Test
        void compactDaysAndOtherDaysHaveNoWindows() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI1, slot(1, 1), BOTH).build(), lesson(KI1, slot(1, 2), BOTH).build(),
                            lesson(KI1, slot(2, 3), BOTH).build(), lesson(KI2, slot(1, 3), BOTH).build())
                    .hasNoImpact();
        }

        @Test
        void subgroupLessonLeavesAWindowForTheOtherSubgroup() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI2, slot(1, 1), BOTH).build(), lesson(KI2, slot(1, 2), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(1, 3), BOTH).build())
                    .penalizesBy(2);
        }

        /** B10: a gap of the whole group is one window per kind of week, not one per subgroup. */
        @Test
        void wholeGroupGapCountsOnceForASplitGroup() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI2, slot(1, 1), BOTH).build(), lesson(KI2, slot(1, 3), BOTH).build())
                    .penalizesBy(2);
        }

        @Test
        void worstSubgroupCountsWhenSubgroupsDiffer() {
            // підгрупа 1: пари 1, 4 (2 вікна); підгрупа 2: пари 1, 2, 4 (1 вікно) — рахується гірша
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI2, slot(1, 1), BOTH).build(), lesson(KI2, slot(1, 4), BOTH).build(),
                            lesson(KI2, slot(1, 2), BOTH).subgroup(2).build())
                    .penalizesBy(4);
            // з порожньою парою 2 й самотньою парою 3 на краю дня: для підгрупи 1 вікно — лише порожня пара 2
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI2, slot(2, 1), BOTH).build(),
                            lesson(KI2, slot(2, 4), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(2, 3), BOTH).subgroup(2).build())
                    .penalizesBy(2);
        }

        /**
         * One teacher's two subgroups one after the other (the soft «Subgroups apart») at the end or the start of the
         * day: the subgroup that waits while the other studies has no window, a lone subgroup pair at the edge of
         * the day is the college's way (DOMAIN §13 Q4).
         */
        @Test
        void loneSubgroupPairsAtTheEdgeOfTheDayAreNoWindow() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI2, slot(1, 1), BOTH).build(), lesson(KI2, slot(1, 2), BOTH).build(),
                            lesson(KI2, slot(1, 3), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(1, 4), BOTH).subgroup(2).build(),
                            lesson(KI2, slot(2, 1), BOTH).subgroup(2).build(),
                            lesson(KI2, slot(2, 2), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(2, 3), BOTH).build(), lesson(KI2, slot(2, 4), BOTH).build(),
                            lesson(KI2, slot(3, 1), BOTH).build(), lesson(KI2, slot(3, 2), BOTH).subgroup(1).build())
                    .hasNoImpact();
        }

        /** Between two pairs of the whole group a lone subgroup pair stays a window for the subgroup that waits. */
        @Test
        void oneTeachersSubgroupsInTheMiddleOfTheDayLeaveWindows() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI2, slot(1, 1), BOTH).build(),
                            lesson(KI2, slot(1, 2), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(1, 3), BOTH).subgroup(2).build(),
                            lesson(KI2, slot(1, 4), BOTH).build())
                    .penalizesBy(2);
        }

        /** Parallel subgroups make a full pair: the day around them is judged like a whole-group day. */
        @Test
        void parallelSubgroupsAreAFullPair() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(1, 1), BOTH).subgroup(2).build(),
                            lesson(KI2, slot(1, 2), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(1, 3), BOTH).build())
                    .penalizesBy(2);
        }

        @Test
        void teacherWindowsCountAcrossGroups() {
            verifier.verifyThat(TimetableConstraintProvider::teacherWindows)
                    .given(lesson(KI1, slot(1, 1), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI2, slot(1, 4), BOTH).teacher(NEPYIVODA).build(),
                            lesson(EA1, slot(2, 5), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI1, slot(2, 6), BOTH).build())
                    .penalizesBy(4);
        }
    }

    /**
     * Owner's rule, round 2 (15.11.2026): Friday is an unloading day, at most the setting «Максимум пар у пʼятницю»
     * (default 3) for every group; the pairs of the group's row count (any subgroup at a lesson).
     */
    @Nested
    class FridayLimit {

        @Test
        void aFourthFridayPairIsHardInBothWeeks() {
            verifier.verifyThat(TimetableConstraintProvider::fridayOverload)
                    .given(lesson(RT1, slot(5, 1), BOTH).build(), lesson(RT1, slot(5, 2), BOTH).build(),
                            lesson(RT1, slot(5, 3), BOTH).build(), lesson(RT1, slot(5, 4), BOTH).build())
                    .penalizesBy(2);
        }

        @Test
        void aHalfAboveTheLimitCountsInItsWeekOnly() {
            verifier.verifyThat(TimetableConstraintProvider::fridayOverload)
                    .given(lesson(RT1, slot(5, 1), BOTH).build(), lesson(RT1, slot(5, 2), BOTH).build(),
                            lesson(RT1, slot(5, 3), BOTH).build(), lesson(RT1, slot(5, 4), NUMERATOR).build())
                    .penalizesBy(1);
        }

        @Test
        void subgroupsOnDifferentPairsAreTwoPairsOfTheGroup() {
            verifier.verifyThat(TimetableConstraintProvider::fridayOverload)
                    .given(lesson(M1, slot(5, 1), BOTH).build(), lesson(M1, slot(5, 2), BOTH).build(),
                            lesson(M1, slot(5, 3), BOTH).subgroup(1).build(),
                            lesson(M1, slot(5, 3), BOTH).subgroup(2).build(),
                            lesson(M1, slot(5, 4), BOTH).subgroup(1).build())
                    .penalizesBy(2);
        }

        @Test
        void otherDaysGroupsWithoutALimitAndThreeFridayPairsAreFine() {
            verifier.verifyThat(TimetableConstraintProvider::fridayOverload)
                    .given(lesson(RT1, slot(4, 1), BOTH).build(), lesson(RT1, slot(4, 2), BOTH).build(),
                            lesson(RT1, slot(4, 3), BOTH).build(), lesson(RT1, slot(4, 4), BOTH).build(),
                            lesson(RT1, slot(5, 1), BOTH).build(), lesson(RT1, slot(5, 2), BOTH).build(),
                            lesson(RT1, slot(5, 3), BOTH).build(),
                            lesson(KI1, slot(5, 1), BOTH).build(), lesson(KI1, slot(5, 2), BOTH).build(),
                            lesson(KI1, slot(5, 3), BOTH).build(), lesson(KI1, slot(5, 4), BOTH).build())
                    .hasNoImpact();
        }

        /** The published 4РТ1 has 4 Friday pairs: pinned outside the scope it does not make the run infeasible. */
        @Test
        void aPinnedFridayAboveTheLimitIsExemptUntilAFreeLessonJoinsIt() {
            List<LessonUnit> pinnedFriday = List.of(lesson(RT1, slot(5, 1), BOTH).pinned().build(),
                    lesson(RT1, slot(5, 2), BOTH).pinned().build(), lesson(RT1, slot(5, 3), BOTH).pinned().build(),
                    lesson(RT1, slot(5, 4), BOTH).pinned().build());
            verifier.verifyThat(TimetableConstraintProvider::fridayOverload)
                    .given(pinnedFriday.toArray())
                    .hasNoImpact();
            List<LessonUnit> withFree = new ArrayList<>(pinnedFriday.subList(0, 3));
            withFree.add(lesson(RT1, slot(5, 4), BOTH).build());
            verifier.verifyThat(TimetableConstraintProvider::fridayOverload)
                    .given(withFree.toArray())
                    .penalizesBy(2);
        }

        /** The limit is the college setting the snapshot carries: 4 lets a fourth pair stand, 2 forbids a third. */
        @Test
        void theLimitFollowsTheSetting() {
            StudentGroup four = new StudentGroup(UUID.randomUUID(), "4РТ2", 25, 1, List.of(), FIRST_SHIFT, 4);
            StudentGroup two = new StudentGroup(UUID.randomUUID(), "1КІ1", 25, 1, List.of(), FIRST_SHIFT, 2);
            verifier.verifyThat(TimetableConstraintProvider::fridayOverload)
                    .given(lesson(four, slot(5, 1), BOTH).build(), lesson(four, slot(5, 2), BOTH).build(),
                            lesson(four, slot(5, 3), BOTH).build(), lesson(four, slot(5, 4), BOTH).build(),
                            lesson(two, slot(5, 1), BOTH).build(), lesson(two, slot(5, 2), BOTH).build(),
                            lesson(two, slot(5, 3), BOTH).build())
                    .penalizesBy(2);
        }

        @Test
        void theLimitIsHardWhateverTheRequestWeights() {
            List<LessonUnit> friday = List.of(lesson(RT1, slot(5, 1), BOTH).build(),
                    lesson(RT1, slot(5, 2), BOTH).build(), lesson(RT1, slot(5, 3), BOTH).build(),
                    lesson(RT1, slot(5, 4), BOTH).build());
            Timetable timetable = new Timetable(List.of(), List.of(ROOM_6), List.of(), List.of(RT1), friday,
                    ConstraintWeights.of(new GenerationWeights(0, 0, 0, 0, 0), 0));

            verifier.verifyThat().givenSolution(timetable).scores(HardSoftScore.ofHard(-2));
        }
    }

    @Nested
    class ShiftWaits {

        @Test
        void waitBetweenAFirstAndASecondShiftLessonIsNotAWindow() {
            List<LessonUnit> day = List.of(lesson(KI1, slot(1, 2), BOTH).teacher(NEPYIVODA).build(),
                    lesson(EA1, slot(1, 6), BOTH).teacher(NEPYIVODA).build());
            verifier.verifyThat(TimetableConstraintProvider::teacherShiftWait).given(day.toArray()).penalizesBy(6);
            verifier.verifyThat(TimetableConstraintProvider::teacherWindows).given(day.toArray()).hasNoImpact();
        }

        @Test
        void gapInsideOneShiftStaysAWindowOnADayWithBothShifts() {
            List<LessonUnit> day = List.of(lesson(KI1, slot(1, 1), BOTH).teacher(NEPYIVODA).build(),
                    lesson(KI1, slot(1, 3), BOTH).teacher(NEPYIVODA).build(),
                    lesson(EA1, slot(1, 6), BOTH).teacher(NEPYIVODA).build());
            verifier.verifyThat(TimetableConstraintProvider::teacherWindows).given(day.toArray()).penalizesBy(2);
            verifier.verifyThat(TimetableConstraintProvider::teacherShiftWait).given(day.toArray()).penalizesBy(4);
        }

        @Test
        void eachKindOfWeekIsJudgedByItsOwnLessons() {
            // чисельник: 1-ша зміна → 1-ша зміна (вікно); знаменник: 1-ша → 2-га (очікування)
            List<LessonUnit> day = List.of(lesson(KI1, slot(5, 1), BOTH).teacher(NEPYIVODA).build(),
                    lesson(KI2, slot(5, 4), NUMERATOR).teacher(NEPYIVODA).build(),
                    lesson(EA1, slot(5, 4), DENOMINATOR).teacher(NEPYIVODA).build());
            verifier.verifyThat(TimetableConstraintProvider::teacherWindows).given(day.toArray()).penalizesBy(2);
            verifier.verifyThat(TimetableConstraintProvider::teacherShiftWait).given(day.toArray()).penalizesBy(2);
        }

        @Test
        void pairWithGroupsOfBothShiftsMakesTheGapAWindow() {
            // пара 4 у п'ятницю спільна для обох змін: різнозмінна пара — це не чисте очікування
            verifier.verifyThat(TimetableConstraintProvider::teacherShiftWait)
                    .given(lesson(KI1, slot(5, 1), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI2, slot(5, 4), BOTH).teacher(NEPYIVODA).build(),
                            lesson(EA1, slot(5, 4), BOTH).teacher(NEPYIVODA).build())
                    .hasNoImpact();
        }
    }

    @Nested
    class DailyBalance {

        @Test
        void groupComingForOnePairInAKindOfWeek() {
            verifier.verifyThat(TimetableConstraintProvider::groupOnePairDays)
                    .given(lesson(KI1, slot(1, 2), BOTH).build(),
                            lesson(KI1, slot(2, 1), BOTH).build(), lesson(KI1, slot(2, 2), NUMERATOR).build(),
                            lesson(KI1, slot(3, 1), BOTH).build(), lesson(KI1, slot(3, 2), BOTH).build())
                    .penalizesBy(3);
        }

        @Test
        void subgroupsOnDifferentPairsGiveTheGroupTwoPairs() {
            verifier.verifyThat(TimetableConstraintProvider::groupOnePairDays)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(1, 2), BOTH).subgroup(2).build())
                    .hasNoImpact();
        }

        @Test
        void teacherComingForOnePairInAKindOfWeek() {
            verifier.verifyThat(TimetableConstraintProvider::teacherOnePairDays)
                    .given(lesson(KI1, slot(1, 2), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI1, slot(2, 1), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI2, slot(2, 2), NUMERATOR).teacher(NEPYIVODA).build(),
                            lesson(KI1, slot(3, 1), BOTH).build())
                    .penalizesBy(3);
        }

        @Test
        void pairsAboveTheTeachersDailyLimit() {
            verifier.verifyThat(TimetableConstraintProvider::teacherOverload)
                    .given(lesson(KI1, slot(1, 1), BOTH).teacher(KOLESNYK).build(),
                            lesson(KI1, slot(1, 2), BOTH).teacher(KOLESNYK).build(),
                            lesson(KI1, slot(1, 3), BOTH).teacher(KOLESNYK).build(),
                            lesson(KI1, slot(1, 4), NUMERATOR).teacher(KOLESNYK).build())
                    .penalizesBy(3);
        }

        @Test
        void teacherWithoutALimitIsNeverOverloaded() {
            verifier.verifyThat(TimetableConstraintProvider::teacherOverload)
                    .given(lesson(KI1, slot(1, 1), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI1, slot(1, 2), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI1, slot(1, 3), BOTH).teacher(NEPYIVODA).build())
                    .hasNoImpact();
        }

        @Test
        void teachingDaysAboveTheBaseline() {
            // база — 2 дні; понеділок, вівторок, середа = +1 у кожному тижні, четвер лише в чисельнику = +1
            verifier.verifyThat(TimetableConstraintProvider::teacherExtraDays)
                    .given(lesson(KI1, slot(1, 1), BOTH).teacher(KOLESNYK).build(),
                            lesson(KI1, slot(2, 1), BOTH).teacher(KOLESNYK).build(),
                            lesson(KI2, slot(3, 1), BOTH).teacher(KOLESNYK).build(),
                            lesson(KI2, slot(4, 2), NUMERATOR).teacher(KOLESNYK).build())
                    .penalizesBy(3);
        }

        @Test
        void daysWithinTheBaselineOrWithoutABaselineAreFine() {
            verifier.verifyThat(TimetableConstraintProvider::teacherExtraDays)
                    .given(lesson(KI1, slot(1, 1), BOTH).teacher(KOLESNYK).build(),
                            lesson(KI1, slot(2, 1), BOTH).teacher(KOLESNYK).build(),
                            lesson(KI1, slot(1, 2), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI1, slot(2, 2), BOTH).teacher(NEPYIVODA).build(),
                            lesson(KI1, slot(3, 2), BOTH).teacher(NEPYIVODA).build())
                    .hasNoImpact();
        }
    }

    @Nested
    class Subgroups {

        @Test
        void subgroupStudyingWhileTheOtherHasNoLesson() {
            verifier.verifyThat(TimetableConstraintProvider::subgroupAlone)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).build(),
                            lesson(KI2, slot(2, 1), NUMERATOR).subgroup(1).build(),
                            lesson(KI2, slot(2, 1), DENOMINATOR).subgroup(2).build())
                    .penalizesBy(4);
        }

        @Test
        void parallelOrCrossedSubgroupsAreTogether() {
            verifier.verifyThat(TimetableConstraintProvider::subgroupAlone)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).discipline(STP).build(),
                            lesson(KI2, slot(1, 1), BOTH).subgroup(2).discipline(STP).build(),
                            lesson(KI2, slot(1, 2), BOTH).subgroup(1).discipline(STP).build(),
                            lesson(KI2, slot(1, 2), BOTH).subgroup(2).discipline(NDEKSM).build(),
                            lesson(KI2, slot(1, 3), BOTH).build(), lesson(KI1, slot(1, 3), BOTH).build())
                    .hasNoImpact();
        }

    }

    /**
     * Owner's rule (15.11.2026): the subgroups of a split discipline study at the same time — hard for two teachers.
     * One teacher of both cannot be parallel: neighbouring pairs, a soft rule with the strongest default.
     */
    @Nested
    class SubgroupsTogether {

        private static final StudentGroup THREE_SUBGROUPS = new StudentGroup(UUID.randomUUID(), "2ТК1", 30, 1,
                List.of(1, 2, 3), FIRST_SHIFT);

        @Test
        void subgroupsOfTwoTeachersInOneSlotAndWeekAreTogether() {
            verifier.verifyThat(TimetableConstraintProvider::subgroupsApart)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("a").build(),
                            lesson(KI2, slot(1, 1), BOTH).subgroup(2).teacher(KRENTSIN).siblingKey("a").build(),
                            lesson(KI2, slot(2, 3), NUMERATOR).subgroup(1).teacher(NEPYIVODA).siblingKey("h").build(),
                            lesson(KI2, slot(2, 3), NUMERATOR).subgroup(2).teacher(KRENTSIN).siblingKey("h").build())
                    .hasNoImpact();
        }

        @Test
        void subgroupsOfTwoTeachersInOtherSlotsOrWeeksBreakTheHardRule() {
            // ціла пара в різних слотах — 2 тижні; половинки в одному слоті, але в різні тижні — 1
            verifier.verifyThat(TimetableConstraintProvider::subgroupsApart)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("a").build(),
                            lesson(KI2, slot(3, 2), BOTH).subgroup(2).teacher(KRENTSIN).siblingKey("a").build(),
                            lesson(KI2, slot(2, 3), NUMERATOR).subgroup(1).teacher(NEPYIVODA).siblingKey("h").build(),
                            lesson(KI2, slot(2, 3), DENOMINATOR).subgroup(2).teacher(KRENTSIN).siblingKey("h").build())
                    .penalizesBy(3);
        }

        @Test
        void oneTeacherTeachesBothSubgroupsOnNeighbouringPairs() {
            verifier.verifyThat(TimetableConstraintProvider::siblingsApart)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("k").build(),
                            lesson(KI2, slot(1, 2), BOTH).subgroup(2).teacher(NEPYIVODA).siblingKey("k").build(),
                            lesson(KI2, slot(2, 4), NUMERATOR).subgroup(1).teacher(NEPYIVODA).siblingKey("h").build(),
                            lesson(KI2, slot(2, 3), NUMERATOR).subgroup(2).teacher(NEPYIVODA).siblingKey("h").build())
                    .hasNoImpact();
        }

        @Test
        void oneTeacherWithAGapOnAnotherDayOrInAnotherWeekIsPenalizedSoftly() {
            // через пару — 2; в інший день — 1; той самий слот у чисельник і знаменник (по черзі) — 1
            LessonUnit[] lessons = {
                    lesson(KI2, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("a").build(),
                    lesson(KI2, slot(1, 3), BOTH).subgroup(2).teacher(NEPYIVODA).siblingKey("a").build(),
                    lesson(KI2, slot(2, 1), NUMERATOR).subgroup(1).teacher(NEPYIVODA).siblingKey("b").build(),
                    lesson(KI2, slot(3, 2), NUMERATOR).subgroup(2).teacher(NEPYIVODA).siblingKey("b").build(),
                    lesson(KI2, slot(4, 1), NUMERATOR).subgroup(1).teacher(NEPYIVODA).siblingKey("d").build(),
                    lesson(KI2, slot(4, 1), DENOMINATOR).subgroup(2).teacher(NEPYIVODA).siblingKey("d").build()};
            verifier.verifyThat(TimetableConstraintProvider::siblingsApart).given((Object[]) lessons).penalizesBy(4);
            verifier.verifyThat(TimetableConstraintProvider::subgroupsApart).given((Object[]) lessons).hasNoImpact();
        }

        @Test
        void aPinnedPairThatIsAlreadyApartIsExempt() {
            // обидві копії закріплені (пари поза вибором) — не рахуємо; одна вільна — рахуємо
            verifier.verifyThat(TimetableConstraintProvider::subgroupsApart)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("p").pinned().build(),
                            lesson(KI2, slot(3, 2), BOTH).subgroup(2).teacher(KRENTSIN).siblingKey("p").pinned().build())
                    .hasNoImpact();
            verifier.verifyThat(TimetableConstraintProvider::subgroupsApart)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("p").pinned().build(),
                            lesson(KI2, slot(3, 2), BOTH).subgroup(2).teacher(KRENTSIN).siblingKey("p").build())
                    .penalizesBy(2);
        }

        @Test
        void lessonsWithoutAKeyOrWithAnotherKeyAreNotSiblings() {
            verifier.verifyThat(TimetableConstraintProvider::subgroupsApart)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("a").build(),
                            lesson(KI2, slot(3, 4), BOTH).subgroup(2).teacher(KRENTSIN).siblingKey("b").build(),
                            lesson(KI2, slot(2, 1), BOTH).subgroup(1).teacher(NEPYIVODA).build(),
                            lesson(KI2, slot(4, 4), BOTH).subgroup(2).teacher(KRENTSIN).build())
                    .hasNoImpact();
        }

        /**
         * A key whose copies cannot all be parallel by the data (here A, A, B: B cannot be parallel to both of A's
         * copies) would make every run infeasible under the hard rule. The factory marks it soft: the hard rule leaves
         * it, and the soft one takes two teachers' copies as together in one slot or on neighbouring pairs.
         */
        @Test
        void aKeyThatCannotBeParallelByTheDataIsLeftToTheSoftRule() {
            // A на 1-й і 2-й парі, B паралельно з першою: краще не буває, а жорстке правило дає −2 за пару «A2—B»
            LessonUnit[] hard = {
                    lesson(THREE_SUBGROUPS, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("t").build(),
                    lesson(THREE_SUBGROUPS, slot(1, 2), BOTH).subgroup(2).teacher(NEPYIVODA).siblingKey("t").build(),
                    lesson(THREE_SUBGROUPS, slot(1, 1), BOTH).subgroup(3).teacher(KRENTSIN).siblingKey("t").build()};
            verifier.verifyThat(TimetableConstraintProvider::subgroupsApart).given((Object[]) hard).penalizesBy(2);

            LessonUnit[] together = {
                    lesson(THREE_SUBGROUPS, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("t")
                            .softSiblingRule().build(),
                    lesson(THREE_SUBGROUPS, slot(1, 2), BOTH).subgroup(2).teacher(NEPYIVODA).siblingKey("t")
                            .softSiblingRule().build(),
                    lesson(THREE_SUBGROUPS, slot(1, 1), BOTH).subgroup(3).teacher(KRENTSIN).siblingKey("t")
                            .softSiblingRule().build()};
            verifier.verifyThat(TimetableConstraintProvider::subgroupsApart).given((Object[]) together).hasNoImpact();
            verifier.verifyThat(TimetableConstraintProvider::siblingsApart).given((Object[]) together).hasNoImpact();

            // B в інший день: дві пари «A—B» не разом, по 2 тижні — лише м'яко
            LessonUnit[] apart = {
                    lesson(THREE_SUBGROUPS, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("t")
                            .softSiblingRule().build(),
                    lesson(THREE_SUBGROUPS, slot(1, 2), BOTH).subgroup(2).teacher(NEPYIVODA).siblingKey("t")
                            .softSiblingRule().build(),
                    lesson(THREE_SUBGROUPS, slot(3, 1), BOTH).subgroup(3).teacher(KRENTSIN).siblingKey("t")
                            .softSiblingRule().build()};
            verifier.verifyThat(TimetableConstraintProvider::subgroupsApart).given((Object[]) apart).hasNoImpact();
            verifier.verifyThat(TimetableConstraintProvider::siblingsApart).given((Object[]) apart).penalizesBy(4);
        }

        /** Two teachers without a common free slot: neighbouring pairs are together, other slots cost softly. */
        @Test
        void twoTeachersOfASoftKeyOnNeighbouringPairsAreTogether() {
            verifier.verifyThat(TimetableConstraintProvider::siblingsApart)
                    .given(lesson(KI2, slot(2, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("n")
                                    .softSiblingRule().build(),
                            lesson(KI2, slot(2, 2), BOTH).subgroup(2).teacher(KRENTSIN).siblingKey("n")
                                    .softSiblingRule().build())
                    .hasNoImpact();
            verifier.verifyThat(TimetableConstraintProvider::siblingsApart)
                    .given(lesson(KI2, slot(2, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("n")
                                    .softSiblingRule().build(),
                            lesson(KI2, slot(2, 3), BOTH).subgroup(2).teacher(KRENTSIN).siblingKey("n")
                                    .softSiblingRule().build())
                    .penalizesBy(2);
        }

        @Test
        void theRuleIsHardWhateverTheRequestWeights() {
            LessonUnit first = lesson(KI2, slot(1, 1), BOTH).subgroup(1).teacher(NEPYIVODA).siblingKey("a").build();
            LessonUnit second = lesson(KI2, slot(2, 1), BOTH).subgroup(2).teacher(KRENTSIN).siblingKey("a").build();
            Timetable zeroWeights = new Timetable(List.of(), List.of(ROOM_6, ROOM_304), List.of(NEPYIVODA, KRENTSIN),
                    List.of(KI2), List.of(first, second), ConstraintWeights.of(
                            new GenerationWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0));
            // 2 тижні жорстко; м'які правила вимкнені нулями, лише «Підгрупа сама» теж має вагу 0
            verifier.verifyThat().givenSolution(zeroWeights).scores(HardSoftScore.ofHard(-2));
        }
    }

    @Nested
    class Stability {

        @Test
        void unitAwayFromItsBaseSlotOrWeek() {
            verifier.verifyThat(TimetableConstraintProvider::unitMoved)
                    .given(lesson(KI1, slot(1, 2), BOTH).base(slot(1, 1), BOTH, ROOM_6.getId()).build(),
                            lesson(KI1, slot(1, 3), DENOMINATOR).base(slot(1, 3), NUMERATOR, null).build(),
                            lesson(KI1, slot(2, 1), BOTH).base(slot(2, 1), BOTH, ROOM_6.getId()).build(),
                            lesson(KI1, slot(2, 2), BOTH).build())
                    .penalizesBy(3);
        }

        @Test
        void unitInItsBaseSlotButAnotherRoom() {
            verifier.verifyThat(TimetableConstraintProvider::roomChanged)
                    .given(lesson(KI1, slot(1, 1), BOTH).room(ROOM_304).base(slot(1, 1), BOTH, ROOM_6.getId()).build(),
                            lesson(KI1, slot(1, 2), NUMERATOR).room(ROOM_304).base(slot(1, 2), NUMERATOR,
                                    ROOM_6.getId()).build(),
                            lesson(KI1, slot(1, 3), BOTH).room(ROOM_304).base(slot(1, 4), BOTH, ROOM_6.getId()).build(),
                            lesson(KI1, slot(2, 1), BOTH).room(ROOM_6).base(slot(2, 1), BOTH, ROOM_6.getId()).build(),
                            lesson(KI1, slot(2, 2), BOTH).room(ROOM_304).base(slot(2, 2), BOTH, null).build())
                    .penalizesBy(3);
        }
    }

    @Nested
    class BrokenDays {

        @Test
        void numeratorOnlyPairMakesTheDayBroken() {
            verifier.verifyThat(TimetableConstraintProvider::brokenDays)
                    .given(lesson(KI1, slot(3, 1), BOTH).build(), lesson(KI1, slot(3, 2), BOTH).build(),
                            lesson(KI1, slot(3, 3), NUMERATOR).build())
                    .penalizesBy(1);
        }

        @Test
        void denominatorOnlyFirstPairChangesCountAndStart() {
            verifier.verifyThat(TimetableConstraintProvider::brokenDays)
                    .given(lesson(KI1, slot(4, 1), DENOMINATOR).build(), lesson(KI1, slot(4, 2), BOTH).build(),
                            lesson(KI1, slot(4, 3), BOTH).build())
                    .penalizesBy(2);
        }

        @Test
        void pairedHalvesKeepTheDayEven() {
            verifier.verifyThat(TimetableConstraintProvider::brokenDays)
                    .given(lesson(KI1, slot(3, 1), BOTH).build(), lesson(KI1, slot(3, 2), NUMERATOR).build(),
                            lesson(KI1, slot(3, 2), DENOMINATOR).build(),
                            lesson(KI2, slot(3, 1), NUMERATOR).subgroup(1).build(),
                            lesson(KI2, slot(3, 1), DENOMINATOR).subgroup(2).build())
                    .hasNoImpact();
        }
    }

    @Nested
    class LatePairs {

        @Test
        void fourthPairOfTheShiftIsLate() {
            verifier.verifyThat(TimetableConstraintProvider::latePairs)
                    .given(lesson(KI1, slot(1, 4), BOTH).build(), lesson(KI2, slot(1, 4), NUMERATOR).build(),
                            lesson(EA1, slot(1, 8), BOTH).build())
                    .penalizesBy(5);
        }

        @Test
        void earlyPairsAndShortFridayOfTheSecondShiftAreFine() {
            verifier.verifyThat(TimetableConstraintProvider::latePairs)
                    .given(lesson(KI1, slot(1, 3), BOTH).build(), lesson(EA1, slot(5, 6), BOTH).build(),
                            lesson(EA1, slot(2, 7), BOTH).build())
                    .hasNoImpact();
        }

        @Test
        void definitionIsTheSharedLatePairsRule() {
            for (int pair = 1; pair <= 8; pair++) {
                LessonUnit unit = lesson(EA1, slot(2, pair), BOTH).build();
                assertThat(unit.isLate()).as("pair %d of shift 2", pair)
                        .isEqualTo(ua.vtc.rozklad.contracts.LatePairs.isLate(pair, 5));
            }
            assertThat(lesson(EA1, slot(5, 6), BOTH).build().isLate()).as("Friday shift 2 has no 4th pair").isFalse();
        }

        @Test
        void laterPairsCostMore() {
            verifier.verifyThat(TimetableConstraintProvider::latePairs)
                    .given(lesson(KI1, slot(1, 6), BOTH).build())
                    .penalizesBy(6);
        }
    }

    @Nested
    class DisciplineTwice {

        @Test
        void wholeGroupTwiceADayAndThreeTimesCountsEachExtraPair() {
            verifier.verifyThat(TimetableConstraintProvider::disciplineTwice)
                    .given(lesson(KI1, slot(1, 1), BOTH).discipline(STP).build(),
                            lesson(KI1, slot(1, 3), BOTH).discipline(STP).build(),
                            lesson(KI1, slot(2, 1), BOTH).discipline(NDEKSM).build(),
                            lesson(KI1, slot(2, 2), BOTH).discipline(NDEKSM).build(),
                            lesson(KI1, slot(2, 3), NUMERATOR).discipline(NDEKSM).build())
                    .penalizesBy(2 + 3);
        }

        @Test
        void subgroupsOneAfterTheOtherAreOncePerStudent() {
            verifier.verifyThat(TimetableConstraintProvider::disciplineTwice)
                    .given(lesson(KI2, slot(1, 1), BOTH).subgroup(1).discipline(STP).build(),
                            lesson(KI2, slot(1, 2), BOTH).subgroup(2).discipline(STP).build(),
                            lesson(KI1, slot(1, 1), BOTH).discipline(STP).build(),
                            lesson(KI1, slot(2, 1), BOTH).discipline(STP).build())
                    .hasNoImpact();
        }

        @Test
        void wholeGroupAndOneSubgroupIsTwiceForThatSubgroup() {
            verifier.verifyThat(TimetableConstraintProvider::disciplineTwice)
                    .given(lesson(KI2, slot(1, 1), BOTH).discipline(STP).build(),
                            lesson(KI2, slot(1, 2), BOTH).subgroup(1).discipline(STP).build())
                    .penalizesBy(2);
        }
    }

    @Nested
    class DisciplineRepeat {

        @Test
        void thirdPairOfOneDisciplineInADay() {
            verifier.verifyThat(TimetableConstraintProvider::disciplineRepeat)
                    .given(lesson(KI1, slot(2, 1), BOTH).discipline(STP).build(),
                            lesson(KI1, slot(2, 2), BOTH).discipline(STP).build(),
                            lesson(KI1, slot(2, 3), NUMERATOR).discipline(STP).build(),
                            lesson(KI1, slot(2, 4), BOTH).discipline(NDEKSM).build())
                    .penalizesBy(1);
        }

        @Test
        void subgroupsInOnePairCountOnce() {
            verifier.verifyThat(TimetableConstraintProvider::disciplineRepeat)
                    .given(lesson(KI2, slot(2, 1), BOTH).subgroup(1).discipline(STP).build(),
                            lesson(KI2, slot(2, 1), BOTH).subgroup(2).discipline(STP).build(),
                            lesson(KI2, slot(2, 2), BOTH).discipline(STP).build(),
                            lesson(KI2, slot(3, 1), BOTH).discipline(STP).build())
                    .hasNoImpact();
        }
    }

    @Nested
    class RoomPreference {

        @Test
        void lessonOutsideItsDesiredRoom() {
            verifier.verifyThat(TimetableConstraintProvider::roomPreference)
                    .given(lesson(KI1, slot(1, 1), BOTH).desiredRoom(ROOM_6).room(ROOM_304).build(),
                            lesson(KI1, slot(1, 2), NUMERATOR).desiredRoom(ROOM_6).room(ROOM_304).build())
                    .penalizesBy(3);
        }

        /**
         * Round 7: core sends the teacher's usual room ({@code teacherRoomId}); the factory makes it
         * the desired room, so a lesson of that teacher in another room is penalized, also when the
         * group has a home room.
         */
        @Test
        void lessonOutsideTheTeachersRoomIsPenalizedNotOutsideTheGroupsHomeRoom() {
            List<ProblemSnapshot.ShiftWindow> windows = new ArrayList<>();
            for (int weekday = 1; weekday <= 5; weekday++) {
                windows.add(new ProblemSnapshot.ShiftWindow(1, weekday, 1, 4));
            }
            List<ProblemSnapshot.Unit> snapshotUnits = List.of(
                    new ProblemSnapshot.Unit("inTeachersRoom", null, KI1.getId(), 0, STP, NEPYIVODA.getId(),
                            UnitWeight.WHOLE, null, null, ROOM_304.getId(), null, null, ROOM_6.getId()),
                    new ProblemSnapshot.Unit("inHomeRoom", null, KI1.getId(), 0, STP, NEPYIVODA.getId(),
                            UnitWeight.WHOLE, null, null, ROOM_304.getId(), null, null, ROOM_6.getId()));
            Timetable timetable = new TimetableProblemFactory(4).create(new ProblemSnapshot(
                    List.of(new ProblemSnapshot.Timeslot(1, 1), new ProblemSnapshot.Timeslot(1, 2)), windows,
                    List.of(new ProblemSnapshot.Group(KI1.getId(), "4КІ1", 20, 1, List.of())),
                    List.of(new ProblemSnapshot.Room(ROOM_6.getId(), "6", RoomType.CLASSROOM, 30, 1),
                            new ProblemSnapshot.Room(ROOM_304.getId(), "304", RoomType.CLASSROOM, 30, 1)),
                    List.of(new ProblemSnapshot.Teacher(NEPYIVODA.getId(), "Непийвода М.В.", List.of(), List.of())),
                    snapshotUnits), GenerationWeights.DEFAULT);
            List<LessonUnit> units = timetable.getUnits();
            Room teachersRoom = units.getFirst().getDesiredRoom();
            Room homeRoom = units.getFirst().getRoomRange().stream()
                    .filter(room -> room.getId().equals(ROOM_304.getId())).findFirst().orElseThrow();
            units.forEach(unit -> unit.setParity(BOTH));
            units.get(0).setTimeslot(slot(1, 1));
            units.get(0).setRoom(teachersRoom);
            units.get(1).setTimeslot(slot(1, 2));
            units.get(1).setRoom(homeRoom);

            verifier.verifyThat(TimetableConstraintProvider::roomPreference)
                    .given(units.toArray())
                    .penalizesBy(2);
        }

        @Test
        void desiredRoomNoPreferenceOrPinnedWithoutRoomAreFine() {
            verifier.verifyThat(TimetableConstraintProvider::roomPreference)
                    .given(lesson(KI1, slot(1, 1), BOTH).desiredRoom(ROOM_6).room(ROOM_6).build(),
                            lesson(KI1, slot(1, 2), BOTH).room(ROOM_304).build(),
                            lesson(KI1, slot(1, 3), BOTH).desiredRoom(ROOM_6).room(Room.NONE).build())
                    .hasNoImpact();
        }
    }

    /**
     * genfix repair: keeping PE in the sports hall or on the ground (data-driven: the load item stood only there in
     * the base version) is a soft rule with its own weight, not a hard room type.
     */
    @Nested
    class SpecialRoomLost {

        private static final Room GROUND = new Room(UUID.randomUUID(), "с.п.", RoomType.SPORTS_GROUND, 0, 1);

        @Test
        void lessonOfAHallItemInAnotherKindOfRoomIsPenalizedPerWeekOccurrence() {
            verifier.verifyThat(TimetableConstraintProvider::specialRoomLost)
                    .given(lesson(KI1, slot(1, 1), BOTH).room(ROOM_304).baseRoomTypes(Set.of(RoomType.GYM)).build(),
                            lesson(KI1, slot(1, 2), NUMERATOR).room(ROOM_6).baseRoomTypes(Set.of(RoomType.GYM))
                                    .build())
                    .penalizesBy(3);
        }

        @Test
        void hallGroundNoBaseTypesOrNoRoomAreFine() {
            verifier.verifyThat(TimetableConstraintProvider::specialRoomLost)
                    .given(lesson(KI1, slot(1, 1), BOTH).room(GYM).baseRoomTypes(Set.of(RoomType.GYM)).build(),
                            lesson(KI1, slot(1, 2), BOTH).room(GROUND)
                                    .baseRoomTypes(Set.of(RoomType.GYM, RoomType.SPORTS_GROUND)).build(),
                            lesson(KI1, slot(1, 3), BOTH).room(ROOM_304).build(),
                            lesson(KI1, slot(2, 1), BOTH).room(Room.NONE).baseRoomTypes(Set.of(RoomType.GYM)).build())
                    .hasNoImpact();
        }

        /** The dispatcher can switch it off: at weight 0 a hall item in a classroom costs nothing, and it is never hard. */
        @Test
        void weightScalesItAndZeroSwitchesItOff() {
            LessonUnit inClassroom = lesson(KI1, slot(1, 1), BOTH).room(ROOM_6)
                    .baseRoomTypes(Set.of(RoomType.GYM)).build();
            LessonUnit next = lesson(KI1, slot(1, 2), BOTH).room(ROOM_6).build();
            GenerationWeights only = new GenerationWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 30);

            verifier.verifyThat().givenSolution(new Timetable(List.of(), List.of(ROOM_6, GYM), List.of(),
                            List.of(KI1), List.of(inClassroom, next), ConstraintWeights.of(only, 0)))
                    .scores(HardSoftScore.ofSoft(-2 * 30));
            GenerationWeights off = new GenerationWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            verifier.verifyThat().givenSolution(new Timetable(List.of(), List.of(ROOM_6, GYM), List.of(),
                            List.of(KI1), List.of(inClassroom, next), ConstraintWeights.of(off, 0)))
                    .scores(HardSoftScore.ZERO);
        }
    }

    @Nested
    class HalfPairing {

        @Test
        void numeratorAndDenominatorOfOneCohortInOneSlotAreRewarded() {
            verifier.verifyThat(TimetableConstraintProvider::halfPairing)
                    .given(lesson(KI1, slot(3, 2), NUMERATOR).discipline(STP).build(),
                            lesson(KI1, slot(3, 2), DENOMINATOR).discipline(NDEKSM).build())
                    .rewardsWith(1);
        }

        @Test
        void otherCombinationsAreNotRewarded() {
            verifier.verifyThat(TimetableConstraintProvider::halfPairing)
                    .given(lesson(KI1, slot(3, 2), NUMERATOR).build(), lesson(KI1, slot(3, 2), BOTH).build(),
                            lesson(KI1, slot(3, 3), NUMERATOR).build(), lesson(KI1, slot(3, 3), NUMERATOR).build(),
                            lesson(KI2, slot(3, 2), NUMERATOR).subgroup(1).build(),
                            lesson(KI2, slot(3, 2), DENOMINATOR).subgroup(2).build(),
                            lesson(EA1, slot(3, 2), DENOMINATOR).build())
                    .hasNoImpact();
        }
    }

    @Test
    void requestWeightsScaleSoftConstraintsAndZeroDisablesThem() {
        LessonUnit late = lesson(KI1, slot(1, 4), BOTH).desiredRoom(ROOM_6).room(ROOM_304).build();
        LessonUnit first = lesson(KI1, slot(1, 1), BOTH).build();
        Timetable weighted = timetable(List.of(late, first), new GenerationWeights(1, 0, 3, 0, 2));
        // вікна групи (жорсткі, без ваги): пари 2 і 3 в обох тижнях = 4 hard; замала аудиторія 2 hard;
        // пізня пара 2×3; не своя аудиторія 2×2
        verifier.verifyThat().givenSolution(weighted).scores(HardSoftScore.of(-4 - 2, -6 - 4));
    }

    @Test
    void newRequestWeightsDriveTheirOwnConstraints() {
        LessonUnit moved = lesson(KI1, slot(1, 2), BOTH).teacher(NEPYIVODA).base(slot(1, 1), BOTH, null).build();
        LessonUnit wait = lesson(EA1, slot(1, 6), BOTH).teacher(NEPYIVODA).build();
        Timetable weighted = new Timetable(List.of(), List.of(ROOM_6, ROOM_304), List.of(NEPYIVODA),
                List.of(KI1, EA1), List.of(moved, wait),
                ConstraintWeights.of(new GenerationWeights(0, 0, 0, 0, 0, 100, 7, 3, 0, 11, 13, 0, 0), 0));
        // очікування між змінами 3 пари × 2 тижні × 7; переставлено 2 × 3;
        // день з однією парою: 2 групи × 2 тижні × 11; викладач має 2 пари — без штрафу днів
        verifier.verifyThat().givenSolution(weighted).scores(HardSoftScore.ofSoft(-6 * 7 - 2 * 3 - 4 * 11));
    }

    // ---- fixtures

    private static Timeslot slot(int weekday, int pair) {
        return new Timeslot(weekday, pair);
    }

    private Timetable timetable(List<LessonUnit> units, GenerationWeights weights) {
        return new Timetable(List.of(), List.of(ROOM_6, ROOM_304), List.of(), List.of(KI1), units,
                ConstraintWeights.of(weights, 5));
    }

    private UnitBuilder lesson(StudentGroup group, Timeslot slot, Parity parity) {
        return new UnitBuilder(group, slot, parity);
    }

    /** Placed unit with sensible defaults: a 30-seat lab, WHOLE weight unless the parity is a half. */
    private final class UnitBuilder {

        private final StudentGroup group;
        private final Timeslot slot;
        private final Parity parity;
        private int subgroup;
        private Teacher teacher;
        private Room room = ROOM_6;
        private RoomType requiredRoomType;
        private Room desiredRoom;
        private UUID discipline = UUID.randomUUID();
        private String siblingKey;
        private boolean softSiblingRule;
        private Timeslot baseSlot;
        private Parity baseParity;
        private UUID baseRoom;
        private Set<RoomType> baseRoomTypes = Set.of();
        private boolean pinned;

        UnitBuilder(StudentGroup group, Timeslot slot, Parity parity) {
            this.group = group;
            this.slot = slot;
            this.parity = parity;
        }

        UnitBuilder subgroup(int subgroup) {
            this.subgroup = subgroup;
            return this;
        }

        UnitBuilder teacher(Teacher teacher) {
            this.teacher = teacher;
            return this;
        }

        UnitBuilder room(Room room) {
            this.room = room;
            return this;
        }

        UnitBuilder requiredRoomType(RoomType requiredRoomType) {
            this.requiredRoomType = requiredRoomType;
            return this;
        }

        UnitBuilder desiredRoom(Room desiredRoom) {
            this.desiredRoom = desiredRoom;
            return this;
        }

        UnitBuilder discipline(UUID discipline) {
            this.discipline = discipline;
            return this;
        }

        UnitBuilder siblingKey(String siblingKey) {
            this.siblingKey = siblingKey;
            return this;
        }

        UnitBuilder softSiblingRule() {
            this.softSiblingRule = true;
            return this;
        }

        UnitBuilder baseRoomTypes(Set<RoomType> types) {
            this.baseRoomTypes = types;
            return this;
        }

        UnitBuilder base(Timeslot slot, Parity parity, UUID roomId) {
            this.baseSlot = slot;
            this.baseParity = parity;
            this.baseRoom = roomId;
            return this;
        }

        UnitBuilder pinned() {
            this.pinned = true;
            return this;
        }

        LessonUnit build() {
            LessonUnit.Builder builder = LessonUnit.builder("u" + ++counter, group, discipline)
                    .subgroup(subgroup)
                    .teacher(teacher)
                    .weight(parity == BOTH ? UnitWeight.WHOLE : UnitWeight.HALF)
                    .requiredRoomType(requiredRoomType)
                    .desiredRoom(desiredRoom)
                    .siblingKey(siblingKey)
                    .softSiblingRule(softSiblingRule)
                    .baseRoomTypes(baseRoomTypes);
            if (pinned) {
                builder.pinnedAt(slot, room, parity);
            } else {
                builder.placedAt(slot, room, parity);
            }
            if (baseSlot != null) {
                builder.base(baseSlot, baseParity, baseRoom);
            }
            return builder.build();
        }
    }
}
