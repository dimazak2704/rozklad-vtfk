package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;

/**
 * DOMAIN §5: an online template lesson keeps its room for reference but does not occupy it. The
 * snapshot says so ({@code units[].online}), so the scheduler does not count the room, and the
 * generated lesson keeps the flag.
 */
class OnlinePinnedUnitTest {

    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID DISCIPLINE = UUID.randomUUID();
    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID ROOM = UUID.randomUUID();
    private static final ProblemSnapshot.PinnedSlot SLOT = new ProblemSnapshot.PinnedSlot(1, 2, Parity.BOTH, ROOM);

    @Test
    void anInScopeUnitPinnedAtAnOnlineLessonIsOnline() {
        PlanUnit pinned = loadUnit(false).pinnedAt(SLOT, UUID.randomUUID(), null, true);

        assertThat(pinned.online()).as("the generated lesson keeps the dispatcher's flag").isTrue();
        assertThat(pinned.toSnapshotUnit(null, null).online()).isTrue();
        assertThat(pinned.toSnapshotUnit(null, null).pinned()).isEqualTo(SLOT);
    }

    @Test
    void anInPersonPinIsNotOnline() {
        assertThat(loadUnit(false).pinnedAt(SLOT, UUID.randomUUID(), null, false).toSnapshotUnit(null, null).online())
                .isFalse();
    }

    @Test
    void aPinnedLessonOutsideTheScopeSendsItsFlag() {
        PlanUnit outside = new PlanUnit("pin:1", null, GROUP, 0, DISCIPLINE, TEACHER, UnitWeight.WHOLE,
                LessonKind.LECTURE, null, null, null, true, SLOT, UUID.randomUUID());

        assertThat(outside.toSnapshotUnit(null, null).online()).isTrue();
    }

    @Test
    void aFreeUnitIsSentInPerson() {
        assertThat(loadUnit(true).toSnapshotUnit(null, SLOT).online()).isFalse();
    }

    private static PlanUnit loadUnit(boolean online) {
        return new PlanUnit("item:0:w1", UUID.randomUUID(), GROUP, 0, DISCIPLINE, TEACHER, UnitWeight.WHOLE,
                LessonKind.LECTURE, null, null, null, online, null, null);
    }
}
