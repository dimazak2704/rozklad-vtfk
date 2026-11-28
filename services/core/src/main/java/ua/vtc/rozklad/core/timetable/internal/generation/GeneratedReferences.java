package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository;

/**
 * The catalog rows and load items a generated timetable refers to, as they exist when the result is applied:
 * the catalog and the load may change during a run.
 */
@Component
class GeneratedReferences {

    private final RoomRepository rooms;
    private final TeacherRepository teachers;
    private final StudentGroupRepository groups;
    private final DisciplineRepository disciplines;
    private final LoadReadRepository loadItems;

    GeneratedReferences(RoomRepository rooms, TeacherRepository teachers, StudentGroupRepository groups,
                        DisciplineRepository disciplines, LoadReadRepository loadItems) {
        this.rooms = rooms;
        this.teachers = teachers;
        this.groups = groups;
        this.disciplines = disciplines;
        this.loadItems = loadItems;
    }

    /**
     * Which referenced rows still exist. Load items are share-locked, so a delete that starts now waits
     * for this transaction instead of breaking its insert.
     */
    Known known(GeneratedLessonsBuilder.Result result) {
        List<PlanUnit> units = result.placements().stream().map(GeneratedLessonsBuilder.Placement::unit).toList();
        Set<UUID> roomIds = result.placements().stream()
                .map(GeneratedReferences::roomOf).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> loadItemIds = ids(units, PlanUnit::loadItemId);
        return new Known(
                rooms.findAllById(roomIds).stream().map(Room::getId).collect(Collectors.toSet()),
                teachers.findAllById(ids(units, PlanUnit::teacherId)).stream().map(Teacher::getId)
                        .collect(Collectors.toSet()),
                groups.findAllById(ids(units, PlanUnit::groupId)).stream()
                        .collect(Collectors.toMap(StudentGroup::getId, GroupRef::of)),
                disciplines.findAllById(ids(units, PlanUnit::disciplineId)).stream().map(Discipline::getId)
                        .collect(Collectors.toSet()),
                loadItems.lockExisting(loadItemIds));
    }

    /** The room a lesson gets: a stream follower was sent without one and takes the stream's room from the base. */
    static UUID roomOf(GeneratedLessonsBuilder.Placement placement) {
        PlanUnit unit = placement.unit();
        return placement.roomId() == null && unit.streamId() != null && unit.pinned() != null
                ? unit.pinned().roomId() : placement.roomId();
    }

    private static Set<UUID> ids(List<PlanUnit> units, Function<PlanUnit, UUID> id) {
        return units.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** Ids of the referenced rows that still exist when the result is applied. */
    record Known(Set<UUID> rooms, Set<UUID> teachers, Map<UUID, GroupRef> groupRefs, Set<UUID> disciplines,
                 Set<UUID> loadItems) {

        Set<UUID> groups() {
            return groupRefs.keySet();
        }
    }
}
