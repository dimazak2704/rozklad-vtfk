package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.core.timetable.TemplateLesson;

/**
 * Free units of the scope start where the base version has them (R4: 2nd-course shifts are nearly
 * full, and placing every unit from scratch left group overlaps the base did not have).
 */
class WarmStartTest {

    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID TOE = UUID.randomUUID();
    private static final UUID MATH = UUID.randomUUID();
    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final UUID ROOM = UUID.randomUUID();

    @Test
    void eachFreeUnitStartsAtOneMatchingBaseLesson() {
        PlanUnit first = unit("toe#1", ITEM, TOE, UnitWeight.WHOLE);
        PlanUnit second = unit("toe#2", ITEM, TOE, UnitWeight.WHOLE);
        PlanUnit half = unit("math#1", null, MATH, UnitWeight.HALF);
        TemplateLesson monday = lesson(1, 5, Parity.BOTH, TOE, ITEM);
        TemplateLesson wednesday = lesson(3, 6, Parity.BOTH, TOE, ITEM);
        TemplateLesson tuesdayDen = lesson(2, 7, Parity.DENOMINATOR, MATH, null);
        TemplateLesson extra = lesson(4, 5, Parity.BOTH, TOE, ITEM);

        Map<String, ProblemSnapshot.PinnedSlot> starts = ProblemSnapshotBuilder.warmStarts(List.of(first, second, half),
                List.of(monday, wednesday, tuesdayDen, extra));

        assertThat(starts).containsOnlyKeys("toe#1", "toe#2", "math#1");
        assertThat(starts.get("toe#1")).isEqualTo(new ProblemSnapshot.PinnedSlot(1, 5, Parity.BOTH, ROOM));
        assertThat(starts.get("toe#2")).isEqualTo(new ProblemSnapshot.PinnedSlot(3, 6, Parity.BOTH, ROOM));
        assertThat(starts.get("math#1")).isEqualTo(new ProblemSnapshot.PinnedSlot(2, 7, Parity.DENOMINATOR, ROOM));
    }

    @Test
    void initialPlacementIsSentOnlyForFreeUnits() {
        ProblemSnapshot.PinnedSlot slot = new ProblemSnapshot.PinnedSlot(1, 5, Parity.BOTH, ROOM);
        PlanUnit free = unit("toe#1", ITEM, TOE, UnitWeight.WHOLE);

        assertThat(free.toSnapshotUnit(null, slot).initial()).isEqualTo(slot);
        assertThat(free.toSnapshotUnit(null, slot).pinned()).isNull();
        assertThat(free.pinnedAt(slot, UUID.randomUUID(), null).toSnapshotUnit(null, slot).initial()).isNull();
    }

    private static PlanUnit unit(String id, UUID loadItem, UUID discipline, UnitWeight weight) {
        return new PlanUnit(id, loadItem, GROUP, 0, discipline, TEACHER, weight, LessonKind.LECTURE, null, null, null,
                false, null, null);
    }

    private static TemplateLesson lesson(int weekday, int pair, Parity parity, UUID discipline, UUID loadItem) {
        TemplateLesson lesson = new TemplateLesson(VERSION, weekday, pair, parity, GROUP, 0, discipline);
        lesson.setTeacherId(TEACHER);
        lesson.setRoomId(ROOM);
        lesson.setLoadItemId(loadItem);
        return lesson;
    }
}
