package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot.PinnedSlot;
import ua.vtc.rozklad.core.catalog.GroupRef;

/** M2/B8: «Переставлено N з M пар» against the base version, with room changes and per group. */
class GenerationOutcomeTest {

    private static final GroupRef KI1 = new GroupRef(UUID.randomUUID(), "4КІ1", 4);
    private static final GroupRef TK = new GroupRef(UUID.randomUUID(), "2ТК", 2);
    private static final UUID ROOM = UUID.randomUUID();
    private static final UUID OTHER_ROOM = UUID.randomUUID();

    @Test
    void movedRoomChangedAndUnplacedUnitsAreCountedAgainstTheBase() {
        PlanUnit kept = unit(KI1, new PinnedSlot(1, 1, Parity.BOTH, ROOM), UnitWeight.WHOLE);
        PlanUnit otherRoom = unit(KI1, new PinnedSlot(1, 2, Parity.BOTH, ROOM), UnitWeight.WHOLE);
        PlanUnit otherDay = unit(KI1, new PinnedSlot(1, 3, Parity.BOTH, ROOM), UnitWeight.WHOLE);
        PlanUnit otherWeek = unit(TK, new PinnedSlot(2, 5, Parity.NUMERATOR, null), UnitWeight.HALF);
        PlanUnit roomless = unit(TK, new PinnedSlot(2, 6, Parity.BOTH, null), UnitWeight.WHOLE);
        PlanUnit unplaced = unit(TK, new PinnedSlot(3, 5, Parity.BOTH, ROOM), UnitWeight.WHOLE);
        PlanUnit noBase = unit(TK, null, UnitWeight.WHOLE);
        GeneratedLessonsBuilder.Result result = new GeneratedLessonsBuilder.Result(List.of(
                placed(kept, 1, 1, Parity.BOTH, ROOM),
                placed(otherRoom, 1, 2, Parity.BOTH, OTHER_ROOM),
                placed(otherDay, 2, 3, Parity.BOTH, ROOM),
                placed(otherWeek, 2, 5, Parity.DENOMINATOR, ROOM),
                placed(roomless, 2, 6, Parity.BOTH, ROOM),
                placed(noBase, 4, 5, Parity.BOTH, ROOM)), List.of(unplaced));

        GenerationOutcome outcome = GenerationOutcome.of(result, GeneratedLessonsBuilder.Placement::roomId,
                Map.of(KI1.id(), KI1, TK.id(), TK));

        assertThat(outcome.total()).isEqualTo(6);
        assertThat(outcome.moved()).as("another day, another week, unplaced").isEqualTo(3);
        assertThat(outcome.roomChanged()).as("a base lesson without a room has no room to change").isEqualTo(1);
        assertThat(outcome.roomAssigned()).as("the roomless base lesson got a room in its own slot").isEqualTo(1);
        assertThat(outcome.added()).as("a load unit the base did not have").isEqualTo(1);
        assertThat(outcome.kept()).isZero();
        assertThat(outcome.perGroup()).containsExactly(
                new GenerationOutcome.GroupOutcome(TK, 2, 3),
                new GenerationOutcome.GroupOutcome(KI1, 1, 3));
    }

    /** The draft holds total - unplaced + added + kept lessons: the UI explains 1077 against «з 1074». */
    @Test
    void pinnedLessonsAreKeptApartFromTheBaseline() {
        PlanUnit free = unit(KI1, new PinnedSlot(1, 1, Parity.BOTH, ROOM), UnitWeight.WHOLE);
        PlanUnit pinned = new PlanUnit("pin:1", null, KI1.id(), 0, UUID.randomUUID(), null, UnitWeight.WHOLE,
                LessonKind.MIXED, null, null, null, false, new PinnedSlot(2, 2, Parity.BOTH, ROOM), UUID.randomUUID());
        GeneratedLessonsBuilder.Result result = new GeneratedLessonsBuilder.Result(List.of(
                placed(free, 1, 1, Parity.BOTH, ROOM), placed(pinned, 2, 2, Parity.BOTH, ROOM)), List.of());

        GenerationOutcome outcome = GenerationOutcome.of(result, GeneratedLessonsBuilder.Placement::roomId,
                Map.of(KI1.id(), KI1));

        assertThat(outcome.total()).isEqualTo(1);
        assertThat(outcome.kept()).isEqualTo(1);
        assertThat(outcome.added()).isZero();
        assertThat(outcome.roomAssigned()).isZero();
    }

    @Test
    void aRunWithoutABaseHasNothingToCompare() {
        GeneratedLessonsBuilder.Result result = new GeneratedLessonsBuilder.Result(
                List.of(placed(unit(KI1, null, UnitWeight.WHOLE), 1, 1, Parity.BOTH, ROOM)), List.of());

        assertThat(GenerationOutcome.of(result, GeneratedLessonsBuilder.Placement::roomId, Map.of()))
                .isEqualTo(new GenerationOutcome(0, 0, 0, List.of(), 0, 1, 0));
    }

    private static PlanUnit unit(GroupRef group, PinnedSlot initial, UnitWeight weight) {
        return new PlanUnit(UUID.randomUUID().toString(), null, group.id(), 0, UUID.randomUUID(), null, weight,
                LessonKind.MIXED, null, null, null, false, null, null, initial, null);
    }

    private static GeneratedLessonsBuilder.Placement placed(PlanUnit unit, int weekday, int pair, Parity parity,
                                                            UUID room) {
        return new GeneratedLessonsBuilder.Placement(unit, weekday, pair, parity, room);
    }
}
