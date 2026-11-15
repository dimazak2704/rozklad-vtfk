package ua.vtc.rozklad.scheduler.solver.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.scheduler.solver.score.ConstraintWeights;

/**
 * Construction order (ascending difficulty; the construction heuristic places the hardest first): the subgroup
 * copies of one sibling key take the difficulty of the hardest of them and come one after another, so the second
 * copy is placed while the slot of the first is still free.
 */
class LessonUnitDifficultyTest {

    private static final ShiftWindow FIRST_SHIFT = new ShiftWindow(List.of(new ShiftWindow.DayRange(1, 1, 4),
            new ShiftWindow.DayRange(2, 1, 4)));
    private static final StudentGroup KI2 = new StudentGroup(UUID.randomUUID(), "1КІ2", 28, 1, List.of(1, 2),
            FIRST_SHIFT);
    private static final Room GYM = new Room(UUID.randomUUID(), "с.з.", RoomType.GYM, 90, 3);
    private static final Room ROOM_304 = new Room(UUID.randomUUID(), "304", RoomType.CLASSROOM, 30, 1);
    private static final Room ROOM_6 = new Room(UUID.randomUUID(), "6", RoomType.CLASSROOM, 30, 1);
    private static final List<Timeslot> SLOTS = List.of(new Timeslot(1, 1), new Timeslot(1, 2), new Timeslot(2, 1));

    @Test
    void siblingCopiesShareTheHardestDifficultyAndComeOneAfterAnother() {
        LessonUnit half = unit("half", UnitWeight.HALF, List.of(GYM, ROOM_304, ROOM_6), 0, null);
        LessonUnit twoRooms = unit("twoRooms", UnitWeight.WHOLE, List.of(ROOM_304, ROOM_6), 0, null);
        LessonUnit oneRoom = unit("oneRoom", UnitWeight.WHOLE, List.of(GYM), 0, null);
        // сама по собі легша за twoRooms, але її копія в ключі «k» — найважча
        LessonUnit easyCopy = unit("easyCopy", UnitWeight.WHOLE, List.of(GYM, ROOM_304, ROOM_6), 1, "k");
        LessonUnit hardCopy = unit("hardCopy", UnitWeight.WHOLE, List.of(GYM), 2, "k");

        assertThat(sorted(half, hardCopy, oneRoom, easyCopy, twoRooms))
                .containsExactly("half", "twoRooms", "oneRoom", "easyCopy", "hardCopy");
    }

    @Test
    void copiesOfTwoKeysWithEqualDifficultyDoNotInterleave() {
        LessonUnit a1 = unit("1", UnitWeight.WHOLE, List.of(ROOM_304), 1, "a");
        LessonUnit b1 = unit("2", UnitWeight.WHOLE, List.of(ROOM_304), 1, "b");
        LessonUnit a2 = unit("3", UnitWeight.WHOLE, List.of(ROOM_304), 2, "a");
        LessonUnit b2 = unit("4", UnitWeight.WHOLE, List.of(ROOM_304), 2, "b");

        assertThat(sorted(b2, a1, b1, a2)).containsExactly("1", "3", "2", "4");
    }

    private static List<String> sorted(LessonUnit... units) {
        Timetable timetable = new Timetable(SLOTS, List.of(GYM, ROOM_304, ROOM_6), List.of(), List.of(KI2),
                List.of(units), ConstraintWeights.of(GenerationWeights.DEFAULT, 5));
        List<LessonUnit> order = new ArrayList<>(List.of(units));
        order.sort(new LessonUnitDifficulty().createComparator(timetable));
        return order.stream().map(LessonUnit::getId).toList();
    }

    private static LessonUnit unit(String id, UnitWeight weight, List<Room> rooms, int subgroup, String siblingKey) {
        return LessonUnit.builder(id, KI2, UUID.randomUUID())
                .subgroup(subgroup)
                .weight(weight)
                .siblingKey(siblingKey)
                .timeslotRange(SLOTS)
                .roomRange(rooms)
                .parityRange(weight == UnitWeight.HALF ? List.of(Parity.NUMERATOR, Parity.DENOMINATOR)
                        : List.of(Parity.BOTH))
                .build();
    }
}
