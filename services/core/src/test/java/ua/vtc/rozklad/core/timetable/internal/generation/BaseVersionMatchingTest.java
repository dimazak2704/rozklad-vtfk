package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.timetable.TemplateLesson;

/**
 * genfix: how the load units of the scope meet their base lessons — the base lesson's teacher is kept
 * (B3), the base pins stay the only pins of the draft (B1), no room type is derived from the base (the
 * scheduler's soft {@code specialRooms} keeps PE in the gym), and the subgroup copies of a split occurrence
 * share a sibling key (M3).
 */
class BaseVersionMatchingTest {

    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID ENGLISH = UUID.randomUUID();
    private static final UUID PE = UUID.randomUUID();
    private static final UUID MOZHAROVSKA = UUID.randomUUID();
    private static final UUID KRYSAK = UUID.randomUUID();
    private static final UUID ENGLISH_ITEM = UUID.randomUUID();
    private static final UUID PE_ITEM = UUID.randomUUID();
    private static final Room ROOM_204 = new Room("204", RoomType.CLASSROOM);
    private static final Room GYM = new Room("с.з.", RoomType.GYM);

    /**
     * B3, 3КІ1 Ін.мова in the real timetable: the load (derived from the timetable) names Крисак for both
     * pairs, the base has one with Можаровська. The unit matched to her lesson keeps her, instead of a silent
     * swap «Додано Крисак / Прибрано Можаровська».
     */
    @Test
    void aUnitMatchedToABaseLessonKeepsItsTeacher() {
        PlanUnit first = unit("eng:0:w1", ENGLISH_ITEM, ENGLISH, 0, KRYSAK);
        PlanUnit second = unit("eng:0:w2", ENGLISH_ITEM, ENGLISH, 0, KRYSAK);
        TemplateLesson withMozharovska = lesson(1, 2, ENGLISH, 0, MOZHAROVSKA, ROOM_204);
        TemplateLesson withKrysak = lesson(3, 2, ENGLISH, 0, KRYSAK, ROOM_204);

        List<PlanUnit> units = ProblemSnapshotBuilder.applyBase(List.of(first, second), List.of(),
                List.of(withMozharovska, withKrysak), Map.of());

        assertThat(units).extracting(PlanUnit::teacherId).containsExactlyInAnyOrder(KRYSAK, MOZHAROVSKA);
        assertThat(units).filteredOn(unit -> MOZHAROVSKA.equals(unit.teacherId())).singleElement()
                .satisfies(unit -> assertThat(unit.initial()).isEqualTo(slot(withMozharovska)));
        assertThat(units).filteredOn(unit -> KRYSAK.equals(unit.teacherId())).singleElement()
                .satisfies(unit -> assertThat(unit.initial()).as("the exact match is not taken by the relaxed one")
                        .isEqualTo(slot(withKrysak)));
    }

    @Test
    void aUnitWithoutABaseLessonKeepsTheLoadTeacherAndHasNoInitialPlacement() {
        PlanUnit unit = unit("eng:0:w1", ENGLISH_ITEM, ENGLISH, 0, KRYSAK);

        assertThat(ProblemSnapshotBuilder.applyBase(List.of(unit), List.of(), List.of(), Map.of()))
                .containsExactly(unit);
    }

    /** B1: a stream lesson is sent pinned (the solver has no streams) but its draft lesson stays free. */
    @Test
    void onlyLessonsPinnedInTheBaseStayPinnedInTheDraft() {
        PlanUnit pinnedByDispatcher = unit("eng:0:w1", ENGLISH_ITEM, ENGLISH, 0, KRYSAK);
        PlanUnit inStream = unit("eng:0:w2", ENGLISH_ITEM, ENGLISH, 0, KRYSAK);
        TemplateLesson pinned = lesson(1, 2, ENGLISH, 0, KRYSAK, ROOM_204);
        pinned.setPinned(true);
        TemplateLesson stream = lesson(2, 2, ENGLISH, 0, KRYSAK, ROOM_204);
        stream.setStreamId(UUID.randomUUID());

        List<PlanUnit> units = ProblemSnapshotBuilder.applyBase(List.of(pinnedByDispatcher, inStream),
                List.of(pinned, stream), List.of(), Map.of());

        assertThat(units).allSatisfy(unit -> assertThat(unit.pinned()).isNotNull());
        assertThat(units).extracting(PlanUnit::pinnedInDraft).containsExactly(true, false);
        assertThat(units).extracting(PlanUnit::initial).containsOnlyNulls();
    }

    @Test
    void aPlanStoredBeforeTheBaseFlagKeepsEveryPinnedUnitPinned() {
        PlanUnit old = new PlanUnit("pin:1", null, GROUP, 0, ENGLISH, KRYSAK, UnitWeight.WHOLE, LessonKind.MIXED,
                null, null, null, false, new ProblemSnapshot.PinnedSlot(1, 1, Parity.BOTH, null), UUID.randomUUID());

        assertThat(old.pinnedInBase()).isNull();
        assertThat(old.pinnedInDraft()).isTrue();
        assertThat(unit("eng:0:w1", ENGLISH_ITEM, ENGLISH, 0, KRYSAK).pinnedInDraft()).isFalse();
    }

    /**
     * genfix repair: every base lesson of the PE item sits in the gym, but the units get no required room type.
     * A derived type was hard (ROOM_TYPE, the B9 pre-check) and could not be switched off; the base room goes in
     * {@code initial} and the scheduler weighs keeping it ({@code specialRooms}).
     */
    @Test
    void aLoadItemWhoseBaseLessonsAllSitInTheGymGetsNoRequiredRoomType() {
        PlanUnit first = unit("pe:0:w1", PE_ITEM, PE, 0, KRYSAK);
        PlanUnit unmatched = unit("pe:0:w2", PE_ITEM, PE, 0, KRYSAK);
        PlanUnit english = unit("eng:0:w1", ENGLISH_ITEM, ENGLISH, 0, KRYSAK);

        List<PlanUnit> units = ProblemSnapshotBuilder.applyBase(List.of(first, unmatched, english), List.of(),
                List.of(lesson(1, 1, PE, 0, KRYSAK, GYM), lesson(1, 2, ENGLISH, 0, KRYSAK, ROOM_204)), Map.of());

        assertThat(units).extracting(PlanUnit::requiredRoomType).containsOnlyNulls();
        assertThat(units).filteredOn(unit -> unit.loadItemId().equals(PE_ITEM) && unit.initial() != null)
                .singleElement()
                .satisfies(unit -> assertThat(unit.initial().roomId()).as("the base room reaches the scheduler")
                        .isEqualTo(GYM.getId()));
    }

    @Test
    void aDispatcherSetRoomTypeIsKept() {
        PlanUnit typed = new PlanUnit("pe:0:w1", PE_ITEM, GROUP, 0, PE, KRYSAK, UnitWeight.WHOLE, LessonKind.MIXED,
                RoomType.SPORTS_GROUND, null, null, false, null, null);

        assertThat(ProblemSnapshotBuilder.applyBase(List.of(typed), List.of(),
                List.of(lesson(1, 1, PE, 0, KRYSAK, GYM)), Map.of()))
                .extracting(PlanUnit::requiredRoomType).containsExactly(RoomType.SPORTS_GROUND);
    }

    /** M3: the subgroup copies of one weekly occurrence share {@code loadItemId:occurrence}. */
    @Test
    void subgroupCopiesOfOneOccurrenceShareASiblingKey() {
        PlanUnit s1 = unit(ENGLISH_ITEM + ":1:w1", ENGLISH_ITEM, ENGLISH, 1, KRYSAK);
        PlanUnit s2 = unit(ENGLISH_ITEM + ":2:w1", ENGLISH_ITEM, ENGLISH, 2, MOZHAROVSKA);
        PlanUnit s2half = unit(ENGLISH_ITEM + ":2:h", ENGLISH_ITEM, ENGLISH, 2, MOZHAROVSKA);
        PlanUnit lone = unit(PE_ITEM + ":1:w1", PE_ITEM, PE, 1, KRYSAK);
        PlanUnit whole = unit(UUID.randomUUID() + ":0:w1", UUID.randomUUID(), PE, 0, KRYSAK);
        Map<UUID, Long> cohorts = Map.of(ENGLISH_ITEM, 2L, PE_ITEM, 1L);

        assertThat(ProblemSnapshotBuilder.siblingKey(s1, cohorts)).isEqualTo(ENGLISH_ITEM + ":w1")
                .isEqualTo(ProblemSnapshotBuilder.siblingKey(s2, cohorts));
        assertThat(ProblemSnapshotBuilder.siblingKey(s2half, cohorts)).isEqualTo(ENGLISH_ITEM + ":h");
        assertThat(ProblemSnapshotBuilder.siblingKey(lone, cohorts)).as("one subgroup only").isNull();
        assertThat(ProblemSnapshotBuilder.siblingKey(whole, cohorts)).isNull();
        assertThat(ProblemSnapshotBuilder.siblingKey(s1.pinnedAt(new ProblemSnapshot.PinnedSlot(1, 1, Parity.BOTH,
                null), UUID.randomUUID(), null), cohorts)).as("pinned").isNull();
        assertThat(s1.toSnapshotUnit(null, null, ENGLISH_ITEM + ":w1").siblingKey()).isEqualTo(ENGLISH_ITEM + ":w1");
    }

    private static PlanUnit unit(String id, UUID item, UUID discipline, int subgroup, UUID teacher) {
        return new PlanUnit(id, item, GROUP, subgroup, discipline, teacher, UnitWeight.WHOLE, LessonKind.MIXED, null,
                null, null, false, null, null);
    }

    private static TemplateLesson lesson(int weekday, int pair, UUID discipline, int subgroup, UUID teacher,
                                         Room room) {
        TemplateLesson lesson = new TemplateLesson(VERSION, weekday, pair, Parity.BOTH, GROUP, subgroup, discipline);
        lesson.setTeacherId(teacher);
        lesson.setRoomId(room.getId());
        return lesson;
    }

    private static ProblemSnapshot.PinnedSlot slot(TemplateLesson lesson) {
        return new ProblemSnapshot.PinnedSlot(lesson.getWeekday(), lesson.getPairNumber(), lesson.getParity(),
                lesson.getRoomId());
    }
}
