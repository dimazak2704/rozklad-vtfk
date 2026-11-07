package ua.vtc.rozklad.core.timetable.internal.support;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.timetable.DisciplineRef;
import ua.vtc.rozklad.core.timetable.GroupRef;
import ua.vtc.rozklad.core.timetable.RoomRef;
import ua.vtc.rozklad.core.timetable.TeacherRef;

/** Loads catalog references for many lessons at once (one query per entity type, no N+1). */
@Component
public class CatalogRefs {

    private final StudentGroupRepository groups;
    private final DisciplineRepository disciplines;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;

    public CatalogRefs(StudentGroupRepository groups, DisciplineRepository disciplines, TeacherRepository teachers,
                       RoomRepository rooms) {
        this.groups = groups;
        this.disciplines = disciplines;
        this.teachers = teachers;
        this.rooms = rooms;
    }

    public Refs load(Collection<UUID> groupIds, Collection<UUID> disciplineIds, Collection<UUID> teacherIds,
                     Collection<UUID> roomIds) {
        return new Refs(
                byId(groups.findAllById(nonNull(groupIds)), StudentGroup::getId, CatalogRefs::groupRef),
                byId(disciplines.findAllById(nonNull(disciplineIds)), Discipline::getId, CatalogRefs::disciplineRef),
                byId(teachers.findAllById(nonNull(teacherIds)), Teacher::getId, CatalogRefs::teacherRef),
                byId(rooms.findAllById(nonNull(roomIds)), Room::getId, CatalogRefs::roomRef));
    }

    public static GroupRef groupRef(StudentGroup group) {
        return new GroupRef(group.getId(), group.getCode(), group.getCourse());
    }

    public static DisciplineRef disciplineRef(Discipline discipline) {
        return new DisciplineRef(discipline.getId(), discipline.getShortName(), discipline.getName(),
                discipline.getColor());
    }

    public static TeacherRef teacherRef(Teacher teacher) {
        return new TeacherRef(teacher.getId(), teacher.getShortName(), teacher.getDisplayName());
    }

    public static RoomRef roomRef(Room room) {
        return new RoomRef(room.getId(), room.getName(), room.getType());
    }

    private static Collection<UUID> nonNull(Collection<UUID> ids) {
        return ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static <E, R> Map<UUID, R> byId(Collection<E> entities, Function<E, UUID> id, Function<E, R> mapper) {
        return entities.stream().collect(Collectors.toUnmodifiableMap(id, mapper));
    }

    /** References by id; a missing id maps to {@code null}. */
    public record Refs(Map<UUID, GroupRef> groups, Map<UUID, DisciplineRef> disciplines, Map<UUID, TeacherRef> teachers,
                       Map<UUID, RoomRef> rooms) {

        public GroupRef group(UUID id) {
            return id == null ? null : groups.get(id);
        }

        public DisciplineRef discipline(UUID id) {
            return id == null ? null : disciplines.get(id);
        }

        public TeacherRef teacher(UUID id) {
            return id == null ? null : teachers.get(id);
        }

        public RoomRef room(UUID id) {
            return id == null ? null : rooms.get(id);
        }
    }
}
