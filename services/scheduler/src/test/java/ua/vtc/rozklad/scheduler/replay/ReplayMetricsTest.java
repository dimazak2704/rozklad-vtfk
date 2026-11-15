package ua.vtc.rozklad.scheduler.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.ShiftWindow;
import ua.vtc.rozklad.scheduler.solver.domain.StudentGroup;
import ua.vtc.rozklad.scheduler.solver.domain.Teacher;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.ConstraintWeights;

/** The metric definitions of the replay harness on a hand-made week (expected values counted by hand). */
class ReplayMetricsTest {

    private static final ShiftWindow FIRST_SHIFT = window(1, 4, 1, 4);
    private static final ShiftWindow SECOND_SHIFT = window(5, 8, 4, 6);
    private static final StudentGroup KI1 = new StudentGroup(UUID.randomUUID(), "4КІ1", 25, 1, List.of(), FIRST_SHIFT);
    private static final StudentGroup KI2 = new StudentGroup(UUID.randomUUID(), "1КІ2", 28, 1, List.of(1, 2),
            FIRST_SHIFT);
    private static final StudentGroup EA1 = new StudentGroup(UUID.randomUUID(), "2ЕА1", 30, 2, List.of(), SECOND_SHIFT);
    private static final Teacher T1 = teacher("Т1", Set.of());
    private static final Teacher T2 = teacher("Т2", Set.of());
    private static final Teacher T3 = teacher("Т3", Set.of());
    private static final Teacher T4 = teacher("Т4", Set.of());
    private static final Teacher T5 = teacher("Т5", Set.of(new Timeslot(5, 4)));
    private static final Room R1 = new Room(UUID.randomUUID(), "304", RoomType.CLASSROOM, 30, 1);
    private static final Room R2 = new Room(UUID.randomUUID(), "6", RoomType.CLASSROOM, 30, 1);
    private static final Room GYM = new Room(UUID.randomUUID(), "с.з.", RoomType.GYM, 90, 5);
    private static final UUID MATH = UUID.randomUUID();
    private static final UUID PE = UUID.randomUUID();
    private static final UUID LAB = UUID.randomUUID();

    @Test
    void countsTheStudyMetricsOfAHandMadeWeek() {
        Timetable week = week();

        ReplayMetrics metrics = ReplayMetrics.of(week.getUnits(), ReplayMetrics.placed(week), Set.of(PE));

        assertThat(metrics.values()).containsExactlyEntriesOf(expected());
    }

    @Test
    void basePlacementsAreThePinnedAndTheBaseSlotsOfFreeUnits() {
        Timetable week = week();

        List<ReplayMetrics.Placement> base = ReplayMetrics.atBase(week);
        ReplayMetrics metrics = ReplayMetrics.of(week.getUnits(), base, Set.of(PE));

        assertThat(base).extracting(placement -> placement.unit().getId()).containsExactly("fri1", "fri2");
        assertThat(base).extracting(ReplayMetrics.Placement::slot)
                .containsExactly(new Timeslot(5, 1), new Timeslot(4, 3));
        assertThat(base.getFirst().room()).isEqualTo(R2);
        assertThat(metrics.get("moved")).isZero();
        assertThat(metrics.get("roomCh")).isZero();
        assertThat(metrics.get("unplaced")).isEqualTo(week.getUnits().size() - 2L);
    }

    private static Map<String, Long> expected() {
        Map<String, Long> values = new java.util.LinkedHashMap<>();
        values.put("grpWin", 8L);   // 4КІ1 Mon p2 ×2, 1КІ2 Thu worst cohort (2: p1,p4) 2 ×2, 2ЕА1 Fri p5 ×2
        values.put("broken", 1L);   // 4КІ1 Fri: numerator {1,2}, denominator {2}
        values.put("late", 4L);     // 4КІ1 Wed p4, 1КІ2 Thu p4 (Friday p6 of the 2nd shift is not late)
        values.put("grp1p", 7L);    // 4КІ1 Tue ×2 and Fri denominator, 2ЕА1 Mon ×2 and Wed ×2
        values.put("grp2p", 5L);    // 4КІ1 Mon ×2 and Fri numerator, 2ЕА1 Fri ×2
        values.put("spread", 18L);  // per week 4КІ1 4−0, 1КІ2 3−0, 2ЕА1 2−0 (days without lessons count 0)
        values.put("coh1p", 7L);
        values.put("lone", 4L);     // 1КІ2 Thu p2 (subgroup 1) and p4 (subgroup 2), both weeks
        values.put("loneScope", 4L); // every unit is free, so every group is in the scope
        values.put("sibApart", 2L); // the two 1КІ2 lab subgroups of Т3 are not on neighbouring pairs (p2, p4), BOTH
        values.put("twice", 4L);    // 4КІ1 Wed maths p1+p2, 2ЕА1 Fri PE p4+p6
        values.put("maxDay", 4L);
        values.put("tchWin", 6L);   // Т1 Mon p2, Т3 Thu p3, Т5 Fri p5 (2ЕА1 both)
        values.put("shWait", 4L);   // Т1 Mon p4–p5 between 4КІ1 (1st shift) and 2ЕА1 (2nd shift)
        values.put("idle", 10L);
        values.put("tchDays", 6L);  // Т1 Mon+Tue, the others one day
        values.put("tchDW", 12L);
        values.put("tch1p", 3L);    // Т1 Tue ×2, Т4 Fri denominator
        values.put("tch5p", 2L);    // Т2 Wed p1–p5
        values.put("unw", 2L);      // Т5 Fri p4
        values.put("peOut", 1L);    // PE in 304
        values.put("moved", 1L);
        values.put("roomCh", 1L);
        values.put("unplaced", 1L);
        return values;
    }

    private static Timetable week() {
        List<LessonUnit> units = new ArrayList<>();
        units.add(placed("mon1", KI1, UUID.randomUUID(), T1, 1, 1, Parity.BOTH, R1).build());
        units.add(placed("mon3", KI1, UUID.randomUUID(), T1, 1, 3, Parity.BOTH, R1).build());
        units.add(placed("mon6", EA1, UUID.randomUUID(), T1, 1, 6, Parity.BOTH, R1).build());
        units.add(placed("tue1", KI1, UUID.randomUUID(), T1, 2, 1, Parity.BOTH, R1).build());
        units.add(placed("wed1", KI1, MATH, T2, 3, 1, Parity.BOTH, R2).build());
        units.add(placed("wed2", KI1, MATH, T2, 3, 2, Parity.BOTH, R2).build());
        units.add(placed("wed3", KI1, UUID.randomUUID(), T2, 3, 3, Parity.BOTH, R2).build());
        units.add(placed("wed4", KI1, UUID.randomUUID(), T2, 3, 4, Parity.BOTH, R2).build());
        units.add(placed("wed5", EA1, UUID.randomUUID(), T2, 3, 5, Parity.BOTH, R2).build());
        units.add(placed("thu1", KI2, UUID.randomUUID(), T3, 4, 1, Parity.BOTH, R1).build());
        units.add(placed("thu2", KI2, LAB, T3, 4, 2, Parity.BOTH, R1).subgroup(1).siblingKey("lab:w1").build());
        units.add(placed("thu4", KI2, LAB, T3, 4, 4, Parity.BOTH, R1).subgroup(2).siblingKey("lab:w1").build());
        units.add(placed("fri1", KI1, UUID.randomUUID(), T4, 5, 1, Parity.NUMERATOR, R1)
                .base(new Timeslot(5, 1), Parity.NUMERATOR, R2.getId()).build());
        units.add(placed("fri2", KI1, UUID.randomUUID(), T4, 5, 2, Parity.BOTH, R1)
                .base(new Timeslot(4, 3), Parity.BOTH, R1.getId()).build());
        units.add(placed("fri4", EA1, PE, T5, 5, 4, Parity.BOTH, R1).build());
        units.add(placed("fri6", EA1, PE, T5, 5, 6, Parity.BOTH, GYM).build());
        units.add(LessonUnit.builder("lost", KI1, UUID.randomUUID()).teacher(T4)
                .timeslotRange(List.of(new Timeslot(1, 2))).roomRange(List.of(R1)).parityRange(List.of(Parity.BOTH))
                .build());
        return new Timetable(List.of(), List.of(R1, R2, GYM), List.of(T1, T2, T3, T4, T5), List.of(KI1, KI2, EA1),
                units, ConstraintWeights.of(GenerationWeights.DEFAULT, 5));
    }

    private static LessonUnit.Builder placed(String id, StudentGroup group, UUID discipline, Teacher teacher,
                                             int weekday, int pair, Parity parity, Room room) {
        return LessonUnit.builder(id, group, discipline).teacher(teacher)
                .placedAt(new Timeslot(weekday, pair), room, parity);
    }

    private static Teacher teacher(String name, Set<Timeslot> unwanted) {
        return new Teacher(UUID.randomUUID(), name, Set.of(), unwanted);
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
