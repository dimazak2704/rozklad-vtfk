package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;

class GeneratedLessonsBuilderTest {

    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID ROOM = UUID.randomUUID();

    private final PlanUnit whole = unit("item:0:w1", UnitWeight.WHOLE, null);
    private final PlanUnit half = unit("item:0:h", UnitWeight.HALF, null);
    private final PlanUnit pinned = unit("pin:lesson", UnitWeight.HALF,
            new ProblemSnapshot.PinnedSlot(4, 1, Parity.DENOMINATOR, ROOM));
    private final GenerationPlan plan = new GenerationPlan(List.of(whole, half, pinned));

    @Test
    void wholeUnitsTakeEveryWeekAndHalvesTheReturnedWeek() {
        GeneratedLessonsBuilder.Result result = GeneratedLessonsBuilder.build(plan, List.of(
                new Assignment(whole.unitId(), 1, 2, Parity.NUMERATOR, ROOM),
                new Assignment(half.unitId(), 3, 3, Parity.DENOMINATOR, null),
                new Assignment(pinned.unitId(), 4, 1, Parity.DENOMINATOR, ROOM)));

        assertThat(result.placements()).extracting(placement -> placement.unit().unitId(),
                        GeneratedLessonsBuilder.Placement::parity)
                .containsExactly(
                        tuple(whole.unitId(), Parity.BOTH),
                        tuple(half.unitId(), Parity.DENOMINATOR),
                        tuple(pinned.unitId(), Parity.DENOMINATOR));
        assertThat(result.unplaced()).isEmpty();
    }

    @Test
    void pinnedUnitLeftOutStaysAtItsSlotAndOthersAreUnplaced() {
        GeneratedLessonsBuilder.Result result = GeneratedLessonsBuilder.build(plan, List.of(
                new Assignment(whole.unitId(), 1, 2, Parity.BOTH, null)));

        assertThat(result.placements()).extracting(placement -> placement.unit().unitId())
                .containsExactly(whole.unitId(), pinned.unitId());
        assertThat(result.placements().get(1)).satisfies(placement -> {
            assertThat(placement.weekday()).isEqualTo(4);
            assertThat(placement.pair()).isEqualTo(1);
            assertThat(placement.roomId()).isEqualTo(ROOM);
        });
        assertThat(result.unplaced()).containsExactly(half);
    }

    @Test
    void halfUnitWithoutWeekParityIsInvalid() {
        assertThatThrownBy(() -> GeneratedLessonsBuilder.build(plan, List.of(
                new Assignment(half.unitId(), 3, 3, Parity.BOTH, null))))
                .isInstanceOf(GeneratedLessonsBuilder.InvalidResultException.class)
                .hasMessageContaining("без чисельника чи знаменника");
    }

    @Test
    void unknownDuplicateAndOffGridAssignmentsAreInvalid() {
        assertThatThrownBy(() -> GeneratedLessonsBuilder.build(plan, List.of(
                new Assignment("ghost", 1, 1, Parity.BOTH, null))))
                .isInstanceOf(GeneratedLessonsBuilder.InvalidResultException.class)
                .hasMessageContaining("невідоме заняття «ghost»");
        assertThatThrownBy(() -> GeneratedLessonsBuilder.build(plan, List.of(
                new Assignment(whole.unitId(), 1, 1, Parity.BOTH, null),
                new Assignment(whole.unitId(), 2, 1, Parity.BOTH, null))))
                .hasMessageContaining("розміщено двічі");
        assertThatThrownBy(() -> GeneratedLessonsBuilder.build(plan, List.of(
                new Assignment(whole.unitId(), 6, 1, Parity.BOTH, null))))
                .hasMessageContaining("поза сіткою");
    }

    private static PlanUnit unit(String id, UnitWeight weight, ProblemSnapshot.PinnedSlot slot) {
        return new PlanUnit(id, null, GROUP, 0, UUID.randomUUID(), null, weight, LessonKind.MIXED, null, null, null,
                false, slot, null);
    }
}
