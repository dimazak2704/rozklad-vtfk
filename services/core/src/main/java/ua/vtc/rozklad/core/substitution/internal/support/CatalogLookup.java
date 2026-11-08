package ua.vtc.rozklad.core.substitution.internal.support;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.SubgroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/** Catalog reads of the substitution module: single lookups with 404 and batch maps for DTOs. */
@Component
public class CatalogLookup {

    private final TeacherRepository teachers;
    private final StudentGroupRepository groups;
    private final DisciplineRepository disciplines;
    private final RoomRepository rooms;
    private final SubgroupRepository subgroups;

    public CatalogLookup(TeacherRepository teachers, StudentGroupRepository groups, DisciplineRepository disciplines,
                         RoomRepository rooms, SubgroupRepository subgroups) {
        this.teachers = teachers;
        this.groups = groups;
        this.disciplines = disciplines;
        this.rooms = rooms;
        this.subgroups = subgroups;
    }

    /** Whether the catalog stores subgroup {@code number} of the group. */
    public boolean hasSubgroup(UUID groupId, int number) {
        return subgroups.findByGroupIdAndNumber(groupId, number).isPresent();
    }

    public Teacher teacher(UUID id) {
        return teachers.findById(id).orElseThrow(() -> NotFoundException.of("Викладача", id));
    }

    public StudentGroup group(UUID id) {
        return groups.findById(id).orElseThrow(() -> NotFoundException.of("Групу", id));
    }

    public Discipline discipline(UUID id) {
        return disciplines.findById(id).orElseThrow(() -> NotFoundException.of("Дисципліну", id));
    }

    public Room room(UUID id) {
        return rooms.findById(id).orElseThrow(() -> NotFoundException.of("Аудиторію", id));
    }

    /*
     * Ids sent in a request body: an unknown id is a 400 on that field (the client can highlight it),
     * while an id in the path stays a 404.
     */

    public Teacher teacher(UUID id, String field) {
        return teachers.findById(id).orElseThrow(() -> FieldValidationException.of(field, "Викладача не знайдено."));
    }

    public StudentGroup group(UUID id, String field) {
        return groups.findById(id).orElseThrow(() -> FieldValidationException.of(field, "Групу не знайдено."));
    }

    public Discipline discipline(UUID id, String field) {
        return disciplines.findById(id).orElseThrow(() -> FieldValidationException.of(field, "Дисципліну не знайдено."));
    }

    public Room room(UUID id, String field) {
        return rooms.findById(id).orElseThrow(() -> FieldValidationException.of(field, "Аудиторію не знайдено."));
    }

    public Map<UUID, Teacher> teachers(Collection<UUID> ids) {
        return byId(teachers.findAllById(nonNull(ids)), Teacher::getId);
    }

    public Map<UUID, StudentGroup> groups(Collection<UUID> ids) {
        return byId(groups.findAllById(nonNull(ids)), StudentGroup::getId);
    }

    public Map<UUID, Discipline> disciplines(Collection<UUID> ids) {
        return byId(disciplines.findAllById(nonNull(ids)), Discipline::getId);
    }

    public Map<UUID, Room> rooms(Collection<UUID> ids) {
        return byId(rooms.findAllById(nonNull(ids)), Room::getId);
    }

    public static TeacherRef ref(Teacher teacher) {
        return teacher == null ? null : TeacherRef.of(teacher);
    }

    public static GroupRef ref(StudentGroup group) {
        return group == null ? null : GroupRef.of(group);
    }

    public static DisciplineRef ref(Discipline discipline) {
        return discipline == null ? null : DisciplineRef.of(discipline);
    }

    public static RoomRef ref(Room room) {
        return room == null ? null : RoomRef.of(room);
    }

    private static List<UUID> nonNull(Collection<UUID> ids) {
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<UUID, T> byId(Collection<T> entities, Function<T, UUID> id) {
        return entities.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
