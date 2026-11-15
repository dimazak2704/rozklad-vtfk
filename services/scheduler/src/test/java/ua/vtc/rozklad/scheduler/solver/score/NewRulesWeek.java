package ua.vtc.rozklad.scheduler.solver.score;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.ShiftWindow;
import ua.vtc.rozklad.scheduler.solver.domain.StudentGroup;
import ua.vtc.rozklad.scheduler.solver.domain.Teacher;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * A hand-made week in which every rule added after the generator review (and the windows and late pairs
 * they interact with) has at least one match; optionally with every unit pinned. «Subgroups not together»
 * is hard since the owner's answers (15.11.2026) and exempts a pair of pinned copies.
 */
final class NewRulesWeek {

    static final Set<TimetableConstraint> RULES = EnumSet.of(TimetableConstraint.GROUP_WINDOWS,
            TimetableConstraint.TEACHER_WINDOWS, TimetableConstraint.TEACHER_SHIFT_WAIT, TimetableConstraint.LATE_PAIR,
            TimetableConstraint.GROUP_ONE_PAIR_DAY, TimetableConstraint.SUBGROUP_ALONE,
            TimetableConstraint.SUBGROUPS_APART, TimetableConstraint.SIBLINGS_APART,
            TimetableConstraint.TEACHER_ONE_PAIR_DAY,
            TimetableConstraint.TEACHER_EXTRA_DAY, TimetableConstraint.TEACHER_OVERLOAD,
            TimetableConstraint.DISCIPLINE_TWICE, TimetableConstraint.UNIT_MOVED, TimetableConstraint.ROOM_CHANGED,
            TimetableConstraint.SPECIAL_ROOM_LOST);
    /** Rules whose matches may consist of pinned units only (two pinned subgroup copies apart are never scored). */
    static final Set<TimetableConstraint> PINNABLE_RULES = withoutSubgroupsApart();

    private static final ShiftWindow FIRST_SHIFT = window(1, 4, 1, 4);
    private static final ShiftWindow SECOND_SHIFT = window(5, 8, 4, 6);
    private static final StudentGroup KI1 = new StudentGroup(UUID.randomUUID(), "4КІ1", 25, 1, List.of(), FIRST_SHIFT);
    private static final StudentGroup KI2 = new StudentGroup(UUID.randomUUID(), "1КІ2", 28, 1, List.of(1, 2),
            FIRST_SHIFT);
    private static final StudentGroup EA1 = new StudentGroup(UUID.randomUUID(), "2ЕА1", 30, 2, List.of(),
            SECOND_SHIFT);
    /** Daily limit 2, baseline one day per kind of week. */
    private static final Teacher LIMITED = new Teacher(UUID.randomUUID(), "Колесник А.І.", Set.of(), Set.of(), 2,
            new int[] {1, 1});
    private static final Teacher FREE = new Teacher(UUID.randomUUID(), "Непийвода М.В.", Set.of(), Set.of());
    private static final Room ROOM_304 = new Room(UUID.randomUUID(), "304", RoomType.CLASSROOM, 30, 1);
    private static final Room ROOM_6 = new Room(UUID.randomUUID(), "6", RoomType.CLASSROOM, 30, 1);
    private static final UUID STP = UUID.randomUUID();

    private NewRulesWeek() {
    }

    private static Set<TimetableConstraint> withoutSubgroupsApart() {
        Set<TimetableConstraint> rules = EnumSet.copyOf(RULES);
        rules.remove(TimetableConstraint.SUBGROUPS_APART);
        rules.remove(TimetableConstraint.SIBLINGS_APART);
        return rules;
    }

    static Timetable timetable(boolean pinned) {
        List<LessonUnit> units = new ArrayList<>();
        // понеділок: викладач FREE — вікно на 2-й парі й очікування до 2-ї зміни; 2ЕА1 приходить на одну пару
        units.add(unit("mon1", KI1, 1, 1, FREE, ROOM_6, pinned).base(slot(1, 1), Parity.BOTH, ROOM_304.getId())
                .build());
        units.add(unit("mon3", KI1, 1, 3, FREE, ROOM_304, pinned).build());
        units.add(unit("mon6", EA1, 1, 6, FREE, ROOM_304, pinned).build());
        // вівторок: у викладача одна пара; у групи вікно і 4-та пара зміни; пара предмета зі спортзалу — в 304
        units.add(unit("tue1", KI1, 2, 1, FREE, ROOM_304, pinned).baseRoomTypes(Set.of(RoomType.GYM)).build());
        units.add(unit("tue4", KI1, 2, 4, null, ROOM_304, pinned).build());
        // середа: обмежений викладач — 3 пари (понад 2), дисципліна двічі, одна пара переставлена з п'ятниці
        units.add(unit("wed1", STP, KI1, 3, 1, LIMITED, ROOM_304, pinned).build());
        units.add(unit("wed2", STP, KI1, 3, 2, LIMITED, ROOM_304, pinned).build());
        units.add(unit("wed3", KI1, 3, 3, LIMITED, ROOM_304, pinned).base(slot(5, 1), Parity.BOTH, null).build());
        // четвер: другий робочий день обмеженого викладача; його підгрупи не підряд і кожна сама
        units.add(unit("thu1", KI2, 4, 1, LIMITED, ROOM_304, pinned).subgroup(1).siblingKey("lab:w1").build());
        units.add(unit("thu3", KI2, 4, 3, LIMITED, ROOM_304, pinned).subgroup(2).siblingKey("lab:w1").build());
        // п'ятниця: англійська двох викладачів, підгрупи не одночасно (жорстке правило)
        units.add(unit("fri2", KI2, 5, 2, FREE, ROOM_304, pinned).subgroup(1).siblingKey("eng:w1").build());
        units.add(unit("fri3", KI2, 5, 3, null, ROOM_6, pinned).subgroup(2).siblingKey("eng:w1").build());
        return new Timetable(List.of(), List.of(ROOM_304, ROOM_6), List.of(LIMITED, FREE), List.of(KI1, KI2, EA1),
                units, ConstraintWeights.of(GenerationWeights.DEFAULT, 5));
    }

    private static LessonUnit.Builder unit(String id, StudentGroup group, int weekday, int pair, Teacher teacher,
                                           Room room, boolean pinned) {
        return unit(id, UUID.randomUUID(), group, weekday, pair, teacher, room, pinned);
    }

    private static LessonUnit.Builder unit(String id, UUID discipline, StudentGroup group, int weekday, int pair,
                                           Teacher teacher, Room room, boolean pinned) {
        LessonUnit.Builder builder = LessonUnit.builder(id, group, discipline)
                .teacher(teacher)
                .weight(UnitWeight.WHOLE);
        return pinned ? builder.pinnedAt(slot(weekday, pair), room, Parity.BOTH)
                : builder.placedAt(slot(weekday, pair), room, Parity.BOTH);
    }

    private static Timeslot slot(int weekday, int pair) {
        return new Timeslot(weekday, pair);
    }

    private static ShiftWindow window(int first, int last, int fridayFirst, int fridayLast) {
        List<ShiftWindow.DayRange> days = new ArrayList<>();
        for (int weekday = 1; weekday <= 4; weekday++) {
            days.add(new ShiftWindow.DayRange(weekday, first, last));
        }
        days.add(new ShiftWindow.DayRange(5, fridayFirst, fridayLast));
        return new ShiftWindow(days);
    }
}
