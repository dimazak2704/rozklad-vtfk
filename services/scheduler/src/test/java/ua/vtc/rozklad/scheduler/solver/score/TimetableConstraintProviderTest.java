package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.contracts.Parity.BOTH;
import static ua.vtc.rozklad.contracts.Parity.DENOMINATOR;
import static ua.vtc.rozklad.contracts.Parity.NUMERATOR;

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

    private static final Teacher KRENTSIN = new Teacher(UUID.randomUUID(), "Кренцін М.Д.",
            Set.of(slot(1, 1)), Set.of(slot(2, 1)));
    private static final Teacher NEPYIVODA = new Teacher(UUID.randomUUID(), "Непийвода М.В.", Set.of(), Set.of());

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

    @Nested
    class Windows {

        @Test
        void freePairBetweenLessonsIsAWindowInBothWeeks() {
            verifier.verifyThat(TimetableConstraintProvider::groupWindows)
                    .given(lesson(KI1, slot(1, 1), BOTH).build(), lesson(KI1, slot(1, 3), BOTH).build())
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
        void laterPairsCostMore() {
            verifier.verifyThat(TimetableConstraintProvider::latePairs)
                    .given(lesson(KI1, slot(1, 6), BOTH).build())
                    .penalizesBy(6);
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
        // вікна: пари 2 і 3 в обох тижнях = 4×1; пізня пара 2×3; не своя аудиторія 2×2; замала аудиторія 2 hard
        verifier.verifyThat().givenSolution(weighted).scores(HardSoftScore.of(-2, -4 - 6 - 4));
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

        LessonUnit build() {
            return LessonUnit.builder("u" + ++counter, group, discipline)
                    .subgroup(subgroup)
                    .teacher(teacher)
                    .weight(parity == BOTH ? UnitWeight.WHOLE : UnitWeight.HALF)
                    .requiredRoomType(requiredRoomType)
                    .desiredRoom(desiredRoom)
                    .placedAt(slot, room, parity)
                    .build();
        }
    }
}
