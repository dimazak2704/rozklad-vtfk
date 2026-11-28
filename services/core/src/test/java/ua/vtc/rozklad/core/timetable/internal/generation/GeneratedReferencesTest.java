package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot.PinnedSlot;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository;

/** The rows a generated timetable refers to, and the room each generated lesson gets. */
class GeneratedReferencesTest {

    private static final UUID ROOM = UUID.randomUUID();
    private static final UUID BASE_ROOM = UUID.randomUUID();
    private static final UUID STREAM = UUID.randomUUID();
    private static final UUID LOAD_ITEM = UUID.randomUUID();

    @Test
    void aPlacedRoomWins() {
        PlanUnit unit = unit(STREAM, new PinnedSlot(1, 1, Parity.BOTH, BASE_ROOM));

        assertThat(GeneratedReferences.roomOf(placed(unit, ROOM))).isEqualTo(ROOM);
    }

    @Test
    void aStreamFollowerSentWithoutARoomTakesTheStreamsRoomFromTheBase() {
        PlanUnit follower = unit(STREAM, new PinnedSlot(1, 1, Parity.BOTH, BASE_ROOM));

        assertThat(GeneratedReferences.roomOf(placed(follower, null))).isEqualTo(BASE_ROOM);
    }

    @Test
    void aLessonOutsideAStreamOrWithoutABaseStaysWithoutARoom() {
        assertThat(GeneratedReferences.roomOf(placed(unit(null, new PinnedSlot(1, 1, Parity.BOTH, BASE_ROOM)), null)))
                .isNull();
        assertThat(GeneratedReferences.roomOf(placed(unit(STREAM, null), null))).isNull();
    }

    @Test
    void knownKeepsOnlyRowsThatStillExistAndLocksTheLoadItems() {
        LoadReadRepository loadItems = mock(LoadReadRepository.class);
        RoomRepository rooms = mock(RoomRepository.class);
        TeacherRepository teachers = mock(TeacherRepository.class);
        StudentGroupRepository groups = mock(StudentGroupRepository.class);
        DisciplineRepository disciplines = mock(DisciplineRepository.class);
        when(rooms.findAllById(anyIterable())).thenReturn(List.of());
        when(teachers.findAllById(anyIterable())).thenReturn(List.of());
        when(groups.findAllById(anyIterable())).thenReturn(List.of());
        when(disciplines.findAllById(anyIterable())).thenReturn(List.of());
        when(loadItems.lockExisting(anySet())).thenReturn(Set.of(LOAD_ITEM));
        GeneratedReferences references = new GeneratedReferences(rooms, teachers, groups, disciplines, loadItems);

        GeneratedReferences.Known known = references.known(new GeneratedLessonsBuilder.Result(
                List.of(placed(unit(null, null), ROOM)), List.of()));

        assertThat(known.rooms()).isEmpty();
        assertThat(known.groups()).isEmpty();
        assertThat(known.loadItems()).containsExactly(LOAD_ITEM);
    }

    private static PlanUnit unit(UUID streamId, PinnedSlot pinned) {
        return new PlanUnit(UUID.randomUUID().toString(), LOAD_ITEM, UUID.randomUUID(), 0, UUID.randomUUID(), null,
                UnitWeight.WHOLE, LessonKind.MIXED, null, null, streamId, false, pinned, null);
    }

    private static GeneratedLessonsBuilder.Placement placed(PlanUnit unit, UUID room) {
        return new GeneratedLessonsBuilder.Placement(unit, 1, 1, Parity.BOTH, room);
    }
}
