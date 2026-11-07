package ua.vtc.rozklad.scheduler.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;

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
        assertThat(roomIds(timetable.getUnits().get(3))).containsExactly(ROOM_304, LAB, GYM);
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

    @Test
    void requestWeightsBecomeConstraintWeightOverrides() {
        Timetable timetable = factory.create(snapshot(List.of(unit("u1").build())),
                new GenerationWeights(1, 2, 3, 4, 5));

        var overrides = timetable.getConstraintWeightOverrides();
        assertThat(overrides.getConstraintWeight(TimetableConstraint.GROUP_WINDOWS.id())).isEqualTo(soft(1));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_WINDOWS.id())).isEqualTo(soft(1));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.BROKEN_DAY.id())).isEqualTo(soft(2));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.HALF_PAIRING.id())).isEqualTo(soft(2));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.LATE_PAIR.id())).isEqualTo(soft(3));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.UNWANTED_SLOT.id())).isEqualTo(soft(4));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.ROOM_PREFERENCE.id())).isEqualTo(soft(5));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.DISCIPLINE_REPEAT.id())).isEqualTo(soft(4));
        assertThat(overrides.getConstraintWeight(TimetableConstraint.TEACHER_CONFLICT.id())).isNull();
    }

    @Test
    void brokenReferencesAreReportedInUkrainian() {
        UUID unknown = UUID.randomUUID();
        List<ProblemSnapshot.Unit> units = List.of(
                unit("u1").group(unknown).build(),
                unit("u2").teacher(unknown).build(),
                unit("u3").preferredRoom(unknown).build(),
                unit("u4").group(SPLIT_GROUP).subgroup(3).build(),
                unit("u5").pinned(new ProblemSnapshot.PinnedSlot(1, 7, Parity.BOTH, null)).build(),
                unit("u5").build());

        assertThatThrownBy(() -> factory.create(snapshot(units), GenerationWeights.DEFAULT))
                .isInstanceOfSatisfying(InvalidProblemException.class, error -> assertThat(error.getErrors())
                        .containsExactly(
                                "Заняття u1 посилається на невідому групу " + unknown + ".",
                                "Заняття u2 посилається на невідомого викладача " + unknown + ".",
                                "Заняття u3 посилається на невідому аудиторію " + unknown + ".",
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
        List<ProblemSnapshot.ShiftWindow> windows = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            windows.add(new ProblemSnapshot.ShiftWindow(1, weekday, 1, 4));
        }
        List<ProblemSnapshot.Timeslot> wholeShift = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            for (int pair = 1; pair <= 4; pair++) {
                wholeShift.add(new ProblemSnapshot.Timeslot(weekday, pair));
            }
        }
        return new ProblemSnapshot(timeslots(), windows,
                List.of(new ProblemSnapshot.Group(GROUP, "4КІ1", 25, 1, List.of()),
                        new ProblemSnapshot.Group(SPLIT_GROUP, "1КІ2", 28, 1, List.of(1, 2))),
                List.of(new ProblemSnapshot.Room(ROOM_304, "304", RoomType.CLASSROOM, 30, 1),
                        new ProblemSnapshot.Room(ROOM_SMALL, "12", RoomType.CLASSROOM, 14, 1),
                        new ProblemSnapshot.Room(LAB, "6", RoomType.COMPUTER_LAB, 0, 1),
                        new ProblemSnapshot.Room(GYM, "с.з.", RoomType.GYM, 90, 3)),
                List.of(new ProblemSnapshot.Teacher(TEACHER, "Кренцін М.Д.",
                                List.of(new ProblemSnapshot.Timeslot(1, 1)), List.of()),
                        new ProblemSnapshot.Teacher(BUSY_TEACHER, "Непийвода М.В.", wholeShift, List.of())),
                units);
    }

    private static UnitBuilder unit(String id) {
        return new UnitBuilder(id);
    }

    private static final class UnitBuilder {
        private final String id;
        private UUID group = GROUP;
        private int subgroup;
        private UUID teacher;
        private UnitWeight weight = UnitWeight.WHOLE;
        private RoomType requiredRoomType;
        private UUID preferredRoom;
        private UUID homeRoom;
        private ProblemSnapshot.PinnedSlot pinned;

        private UnitBuilder(String id) {
            this.id = id;
        }

        UnitBuilder group(UUID group) {
            this.group = group;
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

        UnitBuilder pinned(ProblemSnapshot.PinnedSlot pinned) {
            this.pinned = pinned;
            return this;
        }

        ProblemSnapshot.Unit build() {
            return new ProblemSnapshot.Unit(id, UUID.randomUUID(), group, subgroup, DISCIPLINE, teacher, weight,
                    requiredRoomType, preferredRoom, homeRoom, pinned);
        }
    }
}
