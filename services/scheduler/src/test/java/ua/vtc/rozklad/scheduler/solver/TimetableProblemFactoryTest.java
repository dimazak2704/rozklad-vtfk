package ua.vtc.rozklad.scheduler.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Teacher;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

class TimetableProblemFactoryTest {

    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID SPLIT_GROUP = UUID.randomUUID();
    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID BUSY_TEACHER = UUID.randomUUID();
    private static final UUID ROOM_304 = UUID.randomUUID();
    private static final UUID ROOM_SMALL = UUID.randomUUID();
    private static final UUID LAB = UUID.randomUUID();
    private static final UUID GYM = UUID.randomUUID();
    private static final UUID DISCIPLINE = UUID.randomUUID();

    private final TimetableProblemFactory factory = new TimetableProblemFactory(4);

    @Test
    void timeslotsStayInsideTheShiftWindowAndSkipUnavailableSlots() {
        Timetable timetable = factory.create(snapshot(List.of(unit("u1").teacher(TEACHER).build())),
                GenerationWeights.DEFAULT);

        LessonUnit unit = timetable.getUnits().getFirst();
        assertThat(unit.getTimeslotRange()).allSatisfy(slot -> assertThat(slot.pair()).isBetween(1, 4))
                .doesNotContain(new Timeslot(1, 1))
                .hasSize(5 * 4 - 1);
        assertThat(unit.getTimeslot()).isNull();
        assertThat(unit.isPinned()).isFalse();
    }

    @Test
    void teacherUnavailableForTheWholeShiftFallsBackToTheShiftWindow() {
        Timetable timetable = factory.create(snapshot(List.of(unit("u1").teacher(BUSY_TEACHER).build())),
                GenerationWeights.DEFAULT);

        assertThat(timetable.getUnits().getFirst().getTimeslotRange()).hasSize(5 * 4);
    }

    @Test
    void parityRangeFollowsTheWeight() {
        Timetable timetable = factory.create(snapshot(List.of(unit("whole").build(),
                unit("half").weight(UnitWeight.HALF).build())), GenerationWeights.DEFAULT);

        assertThat(timetable.getUnits().get(0).getParityRange()).containsExactly(Parity.BOTH);
        assertThat(timetable.getUnits().get(1).getParityRange())
                .containsExactly(Parity.NUMERATOR, Parity.DENOMINATOR);
    }

    @Test
    void roomsMatchTheRequiredTypeAndTheGroupSize() {
        Timetable timetable = factory.create(snapshot(List.of(
                unit("lab").requiredRoomType(RoomType.COMPUTER_LAB).build(),
                unit("plain").build(),
                unit("subgroup").group(SPLIT_GROUP).subgroup(1).build(),
                unit("pe").preferredRoom(GYM).build())), GenerationWeights.DEFAULT);

        assertThat(roomIds(timetable.getUnits().get(0))).containsExactly(LAB);
        assertThat(roomIds(timetable.getUnits().get(1))).containsExactly(ROOM_304, LAB);
        assertThat(roomIds(timetable.getUnits().get(2))).containsExactly(ROOM_304, ROOM_SMALL, LAB);
        // бажаний зал без базової версії: спецаудиторія першою, звичайні — для м'якого правила
        assertThat(roomIds(timetable.getUnits().get(3))).containsExactly(GYM, ROOM_304, LAB);
    }

    @Test
    void withoutAFittingRoomEveryRoomIsAllowed() {
        Timetable timetable = factory.create(snapshot(List.of(
                unit("ground").requiredRoomType(RoomType.SPORTS_GROUND).build())), GenerationWeights.DEFAULT);

        assertThat(roomIds(timetable.getUnits().getFirst())).containsExactly(ROOM_304, ROOM_SMALL, LAB, GYM);
    }

    @Test
    void desiredRoomIsThePreferredThenTheHomeRoomAndStartsThere() {
        Timetable timetable = factory.create(snapshot(List.of(
                unit("preferred").preferredRoom(LAB).homeRoom(ROOM_304).build(),
                unit("home").homeRoom(ROOM_304).build(),
                unit("tooSmallHome").homeRoom(ROOM_SMALL).build())), GenerationWeights.DEFAULT);

        assertThat(timetable.getUnits().get(0).getDesiredRoom().getId()).isEqualTo(LAB);
        assertThat(timetable.getUnits().get(0).getRoom().getId()).isEqualTo(LAB);
        assertThat(timetable.getUnits().get(1).getDesiredRoom().getId()).isEqualTo(ROOM_304);
        assertThat(timetable.getUnits().get(2).getDesiredRoom()).isNull();
    }

    /** Round 7: the room belongs to the teacher; the load item's preferred room still comes first. */
    @Test
    void teachersRoomComesAfterThePreferredAndBeforeTheHomeRoom() {
        Timetable timetable = factory.create(snapshot(List.of(
                unit("teacher").teacherRoom(LAB).homeRoom(ROOM_304).build(),
                unit("preferred").preferredRoom(ROOM_304).teacherRoom(LAB).build(),
                unit("tooSmallTeacherRoom").teacherRoom(ROOM_SMALL).homeRoom(ROOM_304).build(),
                unit("peTeacher").teacherRoom(GYM).build())), GenerationWeights.DEFAULT);

        assertThat(timetable.getUnits()).extracting(unit -> unit.getDesiredRoom().getId())
                .containsExactly(LAB, ROOM_304, ROOM_304, GYM);
        assertThat(timetable.getUnits().getFirst().getRoom().getId()).as("warm start in the teacher's room")
                .isEqualTo(LAB);
        // спортзал лише для тих, кому він потрібен або чия це аудиторія; предмет без базових пар, чий викладач
        // веде його в залі, тримається спецаудиторій (зал першим), звичайні лишаються для м'якого правила
        assertThat(roomIds(timetable.getUnits().get(3))).containsExactly(GYM, ROOM_304, LAB);
        assertThat(roomIds(timetable.getUnits().get(0))).doesNotContain(GYM);
    }

    /**
     * Evaluation finding: a lesson in a special-purpose room that is not its desired room (PE on the ground, the
     * teacher's room is the hall) lost that room from its range, started in a classroom and stayed there. A
     * special-purpose base room is now in the range and the warm start keeps it (here the item also has a
     * classroom lesson, so it is not kept to special-purpose rooms as a whole).
     */
    @Test
    void specialPurposeBaseRoomStaysInTheRangeAndIsTheWarmStart() {
        UUID item = UUID.randomUUID();
        Timetable timetable = factory.create(snapshot(List.of(
                unit("peOnTheGround").loadItem(item).teacherRoom(ROOM_304)
                        .initial(new ProblemSnapshot.PinnedSlot(2, 1, Parity.BOTH, GYM)).build(),
                unit("noBase").teacherRoom(ROOM_304).build(),
                unit("sameItemInClass").loadItem(item)
                        .initial(new ProblemSnapshot.PinnedSlot(2, 2, Parity.BOTH, ROOM_304)).build())),
                GenerationWeights.DEFAULT);

        assertThat(roomIds(timetable.getUnits().get(0))).containsExactly(ROOM_304, LAB, GYM);
        assertThat(timetable.getUnits().get(0).getRoom().getId()).isEqualTo(GYM);
        assertThat(timetable.getUnits().get(0).getBaseRoomTypes()).as("the item also has a classroom lesson").isEmpty();
        assertThat(roomIds(timetable.getUnits().get(1))).doesNotContain(GYM);
    }

    /**
     * From scratch there is no base to read, so the teacher's usual room decides (owner's rule, 15.11.2026: PE in the
     * hall or on the ground): an item without a base lesson in a room whose unit names the hall as the teacher's room
     * or the preferred room keeps to both special-purpose types; an item with a classroom base or a classroom teacher
     * room does not.
     */
    @Test
    void withoutABaseTheTeachersHallKeepsTheItemInSpecialPurposeRooms() {
        UUID pe = UUID.randomUUID();
        UUID taughtInClass = UUID.randomUUID();
        Timetable timetable = factory.create(snapshot(List.of(
                unit("peFromScratch").loadItem(pe).teacherRoom(GYM).build(),
                unit("pePreferred").preferredRoom(GYM).build(),
                unit("ordinary").teacherRoom(ROOM_304).build(),
                unit("classBase").loadItem(taughtInClass).teacherRoom(GYM)
                        .initial(new ProblemSnapshot.PinnedSlot(2, 2, Parity.BOTH, ROOM_304)).build())),
                GenerationWeights.DEFAULT);

        assertThat(timetable.getUnits().get(0).getBaseRoomTypes())
                .containsExactlyInAnyOrder(RoomType.GYM, RoomType.SPORTS_GROUND);
        assertThat(timetable.getUnits().get(0).getRoom().getId()).as("starts in the hall").isEqualTo(GYM);
        assertThat(roomIds(timetable.getUnits().get(0)).getFirst()).isEqualTo(GYM);
        assertThat(timetable.getUnits().get(1).getBaseRoomTypes()).isNotEmpty();
        assertThat(timetable.getUnits().get(2).getBaseRoomTypes()).isEmpty();
        assertThat(timetable.getUnits().get(3).getBaseRoomTypes()).as("its base stood in a classroom").isEmpty();
    }

    /**
     * An item whose base lessons all sit in special-purpose rooms gives every free unit (also one without a base)
     * those room types: the unit starts in the hall, and the hall comes first in its range. Ordinary rooms stay in
     * the range (genfix repair: keeping PE in the hall is the soft rule {@code specialRooms}, not a hard narrowing
     * of the range, so a full or closed hall cannot make the run infeasible). An item with a classroom lesson gets
     * no base room types.
     */
    @Test
    void loadItemsBasedOnlyInSpecialPurposeRoomsPreferThemSoftly() {
        UUID pe = UUID.randomUUID();
        UUID mixed = UUID.randomUUID();
        Timetable timetable = factory.create(snapshot(List.of(
                unit("peBase").loadItem(pe).teacherRoom(ROOM_304)
                        .initial(new ProblemSnapshot.PinnedSlot(2, 1, Parity.BOTH, GYM)).build(),
                unit("peNew").loadItem(pe).teacherRoom(ROOM_304).build(),
                unit("mixedGym").loadItem(mixed).initial(new ProblemSnapshot.PinnedSlot(3, 1, Parity.BOTH, GYM))
                        .build(),
                unit("mixedClassroom").loadItem(mixed)
                        .initial(new ProblemSnapshot.PinnedSlot(3, 2, Parity.BOTH, ROOM_304)).build())),
                GenerationWeights.DEFAULT);

        assertThat(roomIds(timetable.getUnits().get(0))).containsExactly(GYM, ROOM_304, LAB);
        assertThat(timetable.getUnits().get(0).getBaseRoomTypes()).containsExactly(RoomType.GYM);
        assertThat(roomIds(timetable.getUnits().get(1))).as("no base, but its item lives in the hall")
                .containsExactly(GYM, ROOM_304, LAB);
        assertThat(timetable.getUnits().get(1).getBaseRoomTypes()).containsExactly(RoomType.GYM);
        assertThat(timetable.getUnits().get(1).getRoom().getId()).as("the warm start prefers the hall").isEqualTo(GYM);
        assertThat(roomIds(timetable.getUnits().get(2))).containsExactly(ROOM_304, LAB, GYM);
        assertThat(timetable.getUnits().get(2).getBaseRoomTypes()).isEmpty();
        assertThat(roomIds(timetable.getUnits().get(3))).containsExactly(ROOM_304, LAB);
    }

    /** A required room type is the load's own hard rule: no base room types are added on top of it. */
    @Test
    void aRequiredRoomTypeGetsNoBaseRoomTypes() {
        UUID pe = UUID.randomUUID();
        Timetable timetable = factory.create(snapshot(List.of(
                unit("typed").loadItem(pe).requiredRoomType(RoomType.GYM)
                        .initial(new ProblemSnapshot.PinnedSlot(2, 1, Parity.BOTH, GYM)).build())),
                GenerationWeights.DEFAULT);

        assertThat(roomIds(timetable.getUnits().get(0))).containsExactly(GYM);
        assertThat(timetable.getUnits().get(0).getBaseRoomTypes()).isEmpty();
    }

    @Test
    void unitsWithoutDesiredRoomStartInTheLeastUsedCompatibleRoom() {
        Timetable timetable = factory.create(snapshot(List.of(
                unit("a").homeRoom(ROOM_304).build(),
                unit("b").build(),
                unit("c").build())), GenerationWeights.DEFAULT);

        // 304 вже має 2 входження (a), тож b іде в LAB; далі нічия — перша за порядком знімка
        assertThat(timetable.getUnits()).extracting(unit -> unit.getRoom().getId())
                .containsExactly(ROOM_304, LAB, ROOM_304);
    }

    /** The base placement is a warm start: placed, not pinned; outside the value range it is ignored. */
    @Test
    void unitsStartAtTheirBasePlacementWithoutBeingPinned() {
        Timetable timetable = factory.create(snapshot(List.of(
                unit("base").teacher(TEACHER).initial(new ProblemSnapshot.PinnedSlot(3, 2, Parity.BOTH, ROOM_SMALL))
                        .build(),
                unit("half").weight(UnitWeight.HALF)
                        .initial(new ProblemSnapshot.PinnedSlot(3, 2, Parity.DENOMINATOR, null)).build(),
                unit("unavailable").teacher(TEACHER)
                        .initial(new ProblemSnapshot.PinnedSlot(1, 1, Parity.BOTH, ROOM_304)).build(),
                unit("wrongParity").initial(new ProblemSnapshot.PinnedSlot(2, 2, Parity.NUMERATOR, ROOM_304))
                        .build())), GenerationWeights.DEFAULT);

        LessonUnit base = timetable.getUnits().get(0);
        assertThat(base.isPinned()).isFalse();
        assertThat(base.getTimeslot()).isEqualTo(new Timeslot(3, 2)).isIn(base.getTimeslotRange());
        assertThat(base.getParity()).isEqualTo(Parity.BOTH);
        assertThat(base.getRoom().getId()).as("a small room is out of range for the whole group").isNotEqualTo(ROOM_SMALL);
        LessonUnit half = timetable.getUnits().get(1);
        assertThat(half.getTimeslot()).isEqualTo(new Timeslot(3, 2));
        assertThat(half.getParity()).isEqualTo(Parity.DENOMINATOR);
        assertThat(half.getRoom()).isNotNull();
        assertThat(timetable.getUnits().get(2).getTimeslot()).as("the teacher is unavailable on Monday 1").isNull();
        assertThat(timetable.getUnits().get(3).getTimeslot()).as("a whole unit cannot be NUMERATOR").isNull();
    }

    @Test
    void pinnedUnitsKeepTheirPlacementAndRoomlessPinsGetNoRoom() {
        Timetable timetable = factory.create(snapshot(List.of(
                unit("withRoom").pinned(new ProblemSnapshot.PinnedSlot(2, 3, Parity.NUMERATOR, ROOM_304)).build(),
                unit("noRoom").weight(UnitWeight.HALF)
                        .pinned(new ProblemSnapshot.PinnedSlot(2, 3, Parity.DENOMINATOR, null)).build())),
                GenerationWeights.DEFAULT);

        LessonUnit withRoom = timetable.getUnits().get(0);
        assertThat(withRoom.isPinned()).isTrue();
        assertThat(withRoom.getTimeslot()).isEqualTo(new Timeslot(2, 3));
        assertThat(withRoom.getParity()).isEqualTo(Parity.NUMERATOR);
        assertThat(withRoom.getRoom().getId()).isEqualTo(ROOM_304);
        assertThat(withRoom.getTimeslotRange()).containsExactly(new Timeslot(2, 3));
        assertThat(timetable.getUnits().get(1).getRoom()).isSameAs(Room.NONE);
    }

    /**
     * A load item may name a room that core no longer sends (deactivated): a desired room is only a
     * preference, so the unit is placed without it instead of failing the whole generation.
     */
    @Test
    void unknownDesiredRoomsAreIgnoredInsteadOfFailingTheJob() {
        UUID deactivated = UUID.randomUUID();
        Timetable timetable = factory.create(snapshot(List.of(
                unit("preferredGone").preferredRoom(deactivated).build(),
                unit("teacherRoomGone").teacherRoom(deactivated).build(),
                unit("homeGone").homeRoom(deactivated).teacherRoom(LAB).build())), GenerationWeights.DEFAULT);

        assertThat(timetable.getUnits().get(0).getDesiredRoom()).isNull();
        assertThat(roomIds(timetable.getUnits().get(0))).containsExactly(ROOM_304, LAB);
        assertThat(timetable.getUnits().get(1).getDesiredRoom()).isNull();
        assertThat(timetable.getUnits().get(2).getDesiredRoom().getId()).isEqualTo(LAB);
    }

    /**
     * DOMAIN §5: an online lesson keeps its room for reference but does not occupy it. Pinned in the
     * same room and slot as an in-person lesson, it causes no room conflict, and the assignment
     * returns its room unchanged.
     */
    @Test
    void onlinePinnedUnitsOccupyNoRoomAndKeepItInTheResult() {
        ProblemSnapshot.PinnedSlot slot = new ProblemSnapshot.PinnedSlot(2, 3, Parity.BOTH, ROOM_SMALL);
        Timetable timetable = factory.create(snapshot(List.of(
                unit("inPerson").group(SPLIT_GROUP).subgroup(1).pinned(slot).build(),
                unit("online").pinned(slot).online(true).build())), GenerationWeights.DEFAULT);

        LessonUnit online = timetable.getUnits().get(1);
        assertThat(online.getRoom()).as("occupies nothing, no capacity or type check").isSameAs(Room.NONE);
        assertThat(ua.vtc.rozklad.scheduler.support.TestSolvers.solutionManager().update(timetable).hardScore())
                .as("room 12 is too small for 4КІ1 and already taken, but the lesson is online").isZero();
        assertThat(TimetableResults.assignments(timetable)).extracting(assignment -> assignment.roomId())
                .containsExactly(ROOM_SMALL, ROOM_SMALL);
    }

    @Test
    void requestWeightsBecomeConstraintWeightOverrides() {
        Timetable timetable = factory.create(snapshot(List.of(unit("u1").build())),
                new GenerationWeights(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14));

        var overrides = timetable.getConstraintWeightOverrides();
        assertThat(overrides.getConstraintWeight(TimetableConstraint.GROUP_WINDOWS.id())).isEqualTo(soft(1));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_WINDOWS.id())).isEqualTo(soft(6));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_SHIFT_WAIT.id())).isEqualTo(soft(7));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.BROKEN_DAY.id())).isEqualTo(soft(2));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.HALF_PAIRING.id())).isEqualTo(soft(2));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.LATE_PAIR.id())).isEqualTo(soft(3));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.UNWANTED_SLOT.id())).isEqualTo(soft(4));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.ROOM_PREFERENCE.id())).isEqualTo(soft(5));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.UNIT_MOVED.id())).isEqualTo(soft(8));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.ROOM_CHANGED.id())).isEqualTo(soft(8));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.SUBGROUP_ALONE.id())).isEqualTo(soft(9));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.SIBLINGS_APART.id())).isEqualTo(soft(9));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.GROUP_ONE_PAIR_DAY.id())).isEqualTo(soft(10));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_ONE_PAIR_DAY.id())).isEqualTo(soft(11));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_EXTRA_DAY.id())).isEqualTo(soft(11));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_OVERLOAD.id())).isEqualTo(soft(12));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.DISCIPLINE_TWICE.id())).isEqualTo(soft(13));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.SPECIAL_ROOM_LOST.id())).isEqualTo(soft(14));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.DISCIPLINE_REPEAT.id())).isEqualTo(soft(4));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_CONFLICT.id())).isNull();
        // підгрупи однієї дисципліни разом — правило коледжу (жорстке): жодна вага запиту його не вимикає
        assertThat(overrides.getConstraintWeight(TimetableConstraint.SUBGROUPS_APART.id())).isNull();
    }

    /** A request of the first schema (five weights): teacher windows follow {@code windows}. */
    @Test
    void oldRequestWeightsKeepOneWindowsWeightAndDefaultTheRest() {
        Timetable timetable = factory.create(snapshot(List.of(unit("u1").build())),
                new GenerationWeights(7, 2, 3, 4, 5));

        var overrides = timetable.getConstraintWeightOverrides();
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_WINDOWS.id())).isEqualTo(soft(7));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_SHIFT_WAIT.id()))
                .isEqualTo(soft(GenerationWeights.DEFAULT_TEACHER_SHIFT_WAIT));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.UNIT_MOVED.id()))
                .isEqualTo(soft(GenerationWeights.DEFAULT_STABILITY));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.SPECIAL_ROOM_LOST.id()))
                .isEqualTo(soft(GenerationWeights.DEFAULT_SPECIAL_ROOMS));
    }

    /**
     * B2: a base lesson without a room starts in a room that is free at its slot, so the run starts
     * without room clashes the base did not have; the desired room is taken only when it is free.
     */
    @Test
    void roomlessBaseLessonsStartInARoomFreeAtTheirSlot() {
        ProblemSnapshot.PinnedSlot tuesday2 = new ProblemSnapshot.PinnedSlot(2, 2, Parity.BOTH, ROOM_304);
        Timetable timetable = factory.create(snapshot(List.of(
                unit("withRoom").teacherRoom(ROOM_304).initial(tuesday2).build(),
                unit("roomless").group(SPLIT_GROUP).subgroup(1).teacherRoom(ROOM_304)
                        .initial(new ProblemSnapshot.PinnedSlot(2, 2, Parity.BOTH, null)).build(),
                unit("roomlessFree").teacherRoom(ROOM_304)
                        .initial(new ProblemSnapshot.PinnedSlot(3, 1, Parity.BOTH, null)).build(),
                unit("pinnedThere").group(SPLIT_GROUP).subgroup(2)
                        .pinned(new ProblemSnapshot.PinnedSlot(2, 2, Parity.BOTH, ROOM_SMALL)).build())),
                GenerationWeights.DEFAULT);

        List<LessonUnit> units = timetable.getUnits();
        assertThat(units.get(0).getRoom().getId()).isEqualTo(ROOM_304);
        assertThat(units.get(1).getRoom().getId()).as("304 and 12 are taken on Tuesday 2").isEqualTo(LAB);
        assertThat(units.get(2).getRoom().getId()).as("the teacher's room is free on Wednesday 1").isEqualTo(ROOM_304);
        assertThat(TestSolvers.solutionManager().update(timetable).hardScore()).isZero();
    }

    /** The base placement stays on the unit for the stability rules, also when it cannot be a warm start. */
    @Test
    void basePlacementIsKeptEvenOutsideTheValueRange() {
        Timetable timetable = factory.create(snapshot(List.of(
                unit("unavailable").teacher(TEACHER)
                        .initial(new ProblemSnapshot.PinnedSlot(1, 1, Parity.BOTH, ROOM_304)).build(),
                unit("noBase").build())), GenerationWeights.DEFAULT);

        LessonUnit unavailable = timetable.getUnits().get(0);
        assertThat(unavailable.getTimeslot()).isNull();
        assertThat(unavailable.getBaseSlot()).isEqualTo(new Timeslot(1, 1));
        assertThat(unavailable.getBaseParity()).isEqualTo(Parity.BOTH);
        assertThat(unavailable.getBaseRoomId()).isEqualTo(ROOM_304);
        assertThat(timetable.getUnits().get(1).hasBase()).isFalse();
    }

    @Test
    void siblingKeysAreKeptOnlyForSubgroupsOfOneGroup() {
        Timetable timetable = factory.create(snapshot(List.of(
                unit("s1").group(SPLIT_GROUP).subgroup(1).siblingKey("lab:w1").build(),
                unit("s2").group(SPLIT_GROUP).subgroup(2).siblingKey("lab:w1").build(),
                unit("otherGroup").subgroup(0).siblingKey("mixed").build(),
                unit("mixed").group(SPLIT_GROUP).subgroup(1).siblingKey("mixed").build(),
                unit("same1").group(SPLIT_GROUP).subgroup(1).siblingKey("same").build(),
                unit("same2").group(SPLIT_GROUP).subgroup(1).siblingKey("same").build(),
                unit("alone").group(SPLIT_GROUP).subgroup(2).siblingKey("alone").build())), GenerationWeights.DEFAULT);

        assertThat(timetable.getUnits()).extracting(LessonUnit::getSiblingKey)
                .containsExactly("lab:w1", "lab:w1", null, null, null, null, null);
    }

    /**
     * The daily limit comes from the snapshot; the day baseline is the weekdays of base and pinned
     * lessons per kind of week, at least the days the load needs at the limit (4 without one).
     */
    @Test
    void teachersGetTheirDailyLimitAndBaseDays() {
        ProblemSnapshot.Teacher limited = new ProblemSnapshot.Teacher(TEACHER, "Кренцін М.Д.", List.of(), List.of(), 3);
        ProblemSnapshot.Teacher unlimited = new ProblemSnapshot.Teacher(BUSY_TEACHER, "Непийвода М.В.", List.of(),
                List.of());
        List<ProblemSnapshot.Unit> units = new ArrayList<>(List.of(
                unit("tue").teacher(TEACHER).initial(new ProblemSnapshot.PinnedSlot(2, 2, Parity.BOTH, null)).build(),
                unit("wedHalf").teacher(TEACHER).weight(UnitWeight.HALF)
                        .initial(new ProblemSnapshot.PinnedSlot(3, 1, Parity.NUMERATOR, null)).build(),
                unit("thuPinned").teacher(TEACHER)
                        .pinned(new ProblemSnapshot.PinnedSlot(4, 3, Parity.BOTH, null)).build()));
        for (int i = 0; i < 5; i++) {
            units.add(unit("free" + i).teacher(BUSY_TEACHER).build());
        }
        Timetable timetable = factory.create(snapshot(units, List.of(limited, unlimited)), GenerationWeights.DEFAULT);

        Teacher withBase = timetable.getTeachers().get(0);
        assertThat(withBase.getMaxPairsPerDay()).isEqualTo(3);
        assertThat(withBase.getBaseDays(LessonUnit.NUMERATOR_WEEK)).isEqualTo(3);
        assertThat(withBase.getBaseDays(LessonUnit.DENOMINATOR_WEEK)).isEqualTo(2);
        Teacher fromLoad = timetable.getTeachers().get(1);
        assertThat(fromLoad.getMaxPairsPerDay()).isNull();
        assertThat(fromLoad.getBaseDays(LessonUnit.NUMERATOR_WEEK)).as("5 pairs at 4 a day").isEqualTo(2);
        assertThat(fromLoad.getBaseDays(LessonUnit.DENOMINATOR_WEEK)).isEqualTo(2);
    }

    /** A snapshot sent by an older core (no daily limit, no sibling key) still reads and builds. */
    @Test
    void snapshotJsonWithoutTheNewFieldsStillBuilds() {
        String json = """
                {"timeslots":[{"weekday":1,"pair":1},{"weekday":1,"pair":2}],
                 "shiftWindows":[{"shift":1,"weekday":1,"firstPair":1,"lastPair":4}],
                 "groups":[{"id":"%s","code":"1КІ2","size":0,"shift":1,"subgroups":[1,2]}],
                 "rooms":[{"id":"%s","name":"304","type":"CLASSROOM","capacity":0,"maxParallel":1}],
                 "teachers":[{"id":"%s","name":"Кренцін М.Д.","unavailable":[],"unwanted":[]}],
                 "units":[{"unitId":"a:1:w1","loadItemId":null,"groupId":"%s","subgroup":1,"disciplineId":"%s",
                           "teacherId":"%s","weight":"WHOLE","requiredRoomType":null,"preferredRoomId":null,
                           "homeRoomId":null,"pinned":null,"initial":null}]}
                """.formatted(SPLIT_GROUP, ROOM_304, TEACHER, SPLIT_GROUP, DISCIPLINE, TEACHER);

        ProblemSnapshot snapshot = JsonMapper.builder().build().readValue(json, ProblemSnapshot.class);
        Timetable timetable = factory.create(snapshot, GenerationWeights.DEFAULT);

        assertThat(snapshot.teachers().getFirst().maxPairsPerDay()).isNull();
        assertThat(snapshot.units().getFirst().siblingKey()).isNull();
        assertThat(timetable.getTeachers().getFirst().getMaxPairsPerDay()).isNull();
        assertThat(timetable.getUnits().getFirst().getSiblingKey()).isNull();
    }

    @Test
    void brokenReferencesAreReportedInUkrainian() {
        UUID unknown = UUID.randomUUID();
        List<ProblemSnapshot.Unit> units = List.of(
                unit("u1").group(unknown).build(),
                unit("u2").teacher(unknown).build(),
                unit("u3").pinned(new ProblemSnapshot.PinnedSlot(1, 2, Parity.BOTH, unknown)).build(),
                unit("u4").group(SPLIT_GROUP).subgroup(3).build(),
                unit("u5").pinned(new ProblemSnapshot.PinnedSlot(1, 7, Parity.BOTH, null)).build(),
                unit("u5").build());

        assertThatThrownBy(() -> factory.create(snapshot(units), GenerationWeights.DEFAULT))
                .isInstanceOfSatisfying(InvalidProblemException.class, error -> assertThat(error.getErrors())
                        .containsExactly(
                                "Заняття u1 посилається на невідому групу " + unknown + ".",
                                "Заняття u2 посилається на невідомого викладача " + unknown + ".",
                                "Заняття u3 закріплене в невідомій аудиторії " + unknown + ".",
                                "Заняття u4: група 1КІ2 не має підгрупи 3.",
                                "Заняття u5 закріплене у слоті «день 1, пара 7», якого немає в розкладі дзвінків.",
                                "Заняття u5 повторюється у знімку."))
                .hasMessageStartingWith("Некоректні вхідні дані генерації.")
                .hasMessageContaining("…і ще 1.");
    }

    @Test
    void missingShiftWindowsRoomsAndUnitsAreReported() {
        ProblemSnapshot snapshot = new ProblemSnapshot(timeslots(), List.of(),
                List.of(new ProblemSnapshot.Group(GROUP, "4КІ1", 25, 1, List.of())), List.of(), List.of(),
                List.of(unit("u1").build()));

        assertThatThrownBy(() -> factory.create(snapshot, GenerationWeights.DEFAULT))
                .isInstanceOfSatisfying(InvalidProblemException.class, error -> assertThat(error.getErrors())
                        .containsExactly("Для зміни 1 (група 4КІ1) не задано вікон занять.",
                                "У знімку немає жодної аудиторії — заняттям нікуди стати."));

        ProblemSnapshot empty = new ProblemSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> factory.create(empty, GenerationWeights.DEFAULT))
                .isInstanceOfSatisfying(InvalidProblemException.class, error -> assertThat(error.getErrors())
                        .containsExactly("Не задано жодного слоту розкладу (день і пара).",
                                "Немає жодного заняття для розміщення."));
    }

    @Test
    void invalidRoomsTeachersAndWindowsAreReported() {
        ProblemSnapshot snapshot = new ProblemSnapshot(timeslots(),
                List.of(new ProblemSnapshot.ShiftWindow(1, 1, 3, 2), new ProblemSnapshot.ShiftWindow(1, 2, 1, 4),
                        new ProblemSnapshot.ShiftWindow(1, 2, 1, 4)),
                List.of(new ProblemSnapshot.Group(GROUP, "4КІ1", -1, 1, List.of(2, 2))),
                List.of(new ProblemSnapshot.Room(ROOM_304, "304", RoomType.CLASSROOM, -5, 0)),
                List.of(new ProblemSnapshot.Teacher(TEACHER, "Кренцін М.Д.", List.of(new ProblemSnapshot.Timeslot(6, 1)),
                        List.of())),
                List.of(unit("u1").build()));

        assertThatThrownBy(() -> factory.create(snapshot, GenerationWeights.DEFAULT))
                .isInstanceOfSatisfying(InvalidProblemException.class, error -> assertThat(error.getErrors())
                        .containsExactly("Вікно зміни 1 на день 1 має некоректні пари 3–2.",
                                "Для зміни 1 день 2 задано більше одного вікна.",
                                "Група 4КІ1 має від'ємну кількість студентів.",
                                "Група 4КІ1 має некоректні номери підгруп [2, 2].",
                                "Аудиторія 304: кількість паралельних занять має бути не менше 1.",
                                "Аудиторія 304 має від'ємну місткість.",
                                "Побажання викладача Кренцін М.Д. містять некоректний слот «день 6, пара 1»."));
    }

    // ---- fixtures

    private static HardSoftScore soft(int weight) {
        return HardSoftScore.ofSoft(weight);
    }

    private static List<UUID> roomIds(LessonUnit unit) {
        return unit.getRoomRange().stream().map(Room::getId).toList();
    }

    private static List<ProblemSnapshot.Timeslot> timeslots() {
        List<ProblemSnapshot.Timeslot> slots = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            for (int pair = 1; pair <= 6; pair++) {
                slots.add(new ProblemSnapshot.Timeslot(weekday, pair));
            }
        }
        return slots;
    }

    private static ProblemSnapshot snapshot(List<ProblemSnapshot.Unit> units) {
        return snapshot(units, List.of(new ProblemSnapshot.Teacher(TEACHER, "Кренцін М.Д.",
                        List.of(new ProblemSnapshot.Timeslot(1, 1)), List.of()),
                new ProblemSnapshot.Teacher(BUSY_TEACHER, "Непийвода М.В.", wholeShift(), List.of())));
    }

    private static List<ProblemSnapshot.Timeslot> wholeShift() {
        List<ProblemSnapshot.Timeslot> wholeShift = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            for (int pair = 1; pair <= 4; pair++) {
                wholeShift.add(new ProblemSnapshot.Timeslot(weekday, pair));
            }
        }
        return wholeShift;
    }

    private static ProblemSnapshot snapshot(List<ProblemSnapshot.Unit> units, List<ProblemSnapshot.Teacher> teachers) {
        List<ProblemSnapshot.ShiftWindow> windows = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            windows.add(new ProblemSnapshot.ShiftWindow(1, weekday, 1, 4));
        }
        return new ProblemSnapshot(timeslots(), windows,
                List.of(new ProblemSnapshot.Group(GROUP, "4КІ1", 25, 1, List.of()),
                        new ProblemSnapshot.Group(SPLIT_GROUP, "1КІ2", 28, 1, List.of(1, 2))),
                List.of(new ProblemSnapshot.Room(ROOM_304, "304", RoomType.CLASSROOM, 30, 1),
                        new ProblemSnapshot.Room(ROOM_SMALL, "12", RoomType.CLASSROOM, 14, 1),
                        new ProblemSnapshot.Room(LAB, "6", RoomType.COMPUTER_LAB, 0, 1),
                        new ProblemSnapshot.Room(GYM, "с.з.", RoomType.GYM, 90, 3)),
                teachers,
                units);
    }

    private static UnitBuilder unit(String id) {
        return new UnitBuilder(id);
    }

    private static final class UnitBuilder {
        private final String id;
        private UUID group = GROUP;
        private UUID loadItem = UUID.randomUUID();
        private int subgroup;
        private UUID teacher;
        private UnitWeight weight = UnitWeight.WHOLE;
        private RoomType requiredRoomType;
        private UUID preferredRoom;
        private UUID homeRoom;
        private UUID teacherRoom;
        private ProblemSnapshot.PinnedSlot pinned;
        private ProblemSnapshot.PinnedSlot initial;
        private boolean online;
        private String siblingKey;

        private UnitBuilder(String id) {
            this.id = id;
        }

        UnitBuilder group(UUID group) {
            this.group = group;
            return this;
        }

        UnitBuilder loadItem(UUID loadItem) {
            this.loadItem = loadItem;
            return this;
        }

        UnitBuilder subgroup(int subgroup) {
            this.subgroup = subgroup;
            return this;
        }

        UnitBuilder teacher(UUID teacher) {
            this.teacher = teacher;
            return this;
        }

        UnitBuilder weight(UnitWeight weight) {
            this.weight = weight;
            return this;
        }

        UnitBuilder requiredRoomType(RoomType requiredRoomType) {
            this.requiredRoomType = requiredRoomType;
            return this;
        }

        UnitBuilder preferredRoom(UUID preferredRoom) {
            this.preferredRoom = preferredRoom;
            return this;
        }

        UnitBuilder homeRoom(UUID homeRoom) {
            this.homeRoom = homeRoom;
            return this;
        }

        UnitBuilder teacherRoom(UUID teacherRoom) {
            this.teacherRoom = teacherRoom;
            return this;
        }

        UnitBuilder pinned(ProblemSnapshot.PinnedSlot pinned) {
            this.pinned = pinned;
            return this;
        }

        UnitBuilder initial(ProblemSnapshot.PinnedSlot initial) {
            this.initial = initial;
            return this;
        }

        UnitBuilder online(boolean online) {
            this.online = online;
            return this;
        }

        UnitBuilder siblingKey(String siblingKey) {
            this.siblingKey = siblingKey;
            return this;
        }

        ProblemSnapshot.Unit build() {
            return new ProblemSnapshot.Unit(id, loadItem, group, subgroup, DISCIPLINE, teacher, weight,
                    requiredRoomType, preferredRoom, homeRoom, pinned, initial, teacherRoom, online, siblingKey);
        }
    }
}
