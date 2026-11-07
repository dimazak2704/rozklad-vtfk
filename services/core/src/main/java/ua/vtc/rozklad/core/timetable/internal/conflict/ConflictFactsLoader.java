package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import ua.vtc.rozklad.core.catalog.Subgroup;
import ua.vtc.rozklad.core.catalog.SubgroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.timetable.internal.calendar.ShiftWindowsLoader;
import ua.vtc.rozklad.core.timetable.internal.support.PreferenceReadRepository;

/** Loads the {@link ConflictFacts} of the entities referenced by a set of lessons (bulk, no N+1). */
@Component
public class ConflictFactsLoader {

    private final StudentGroupRepository groups;
    private final SubgroupRepository subgroups;
    private final RoomRepository rooms;
    private final TeacherRepository teachers;
    private final DisciplineRepository disciplines;
    private final ShiftWindowsLoader shiftWindows;
    private final PreferenceReadRepository preferences;

    public ConflictFactsLoader(StudentGroupRepository groups, SubgroupRepository subgroups, RoomRepository rooms,
                               TeacherRepository teachers, DisciplineRepository disciplines,
                               ShiftWindowsLoader shiftWindows, PreferenceReadRepository preferences) {
        this.groups = groups;
        this.subgroups = subgroups;
        this.rooms = rooms;
        this.teachers = teachers;
        this.disciplines = disciplines;
        this.shiftWindows = shiftWindows;
        this.preferences = preferences;
    }

    /** Facts for the lessons; teacher preferences of {@code semesterId} ({@code null} = none). */
    public ConflictFacts load(Collection<SlotLesson> lessons, UUID semesterId) {
        Set<UUID> groupIds = ids(lessons, SlotLesson::groupId);
        Set<UUID> roomIds = ids(lessons, SlotLesson::roomId);
        Set<UUID> teacherIds = ids(lessons, SlotLesson::teacherId);
        Set<UUID> disciplineIds = ids(lessons, SlotLesson::disciplineId);
        return new ConflictFacts(groupFacts(groupIds),
                rooms.findAllById(roomIds).stream().collect(Collectors.toMap(Room::getId, ConflictFactsLoader::roomFact)),
                teachers.findAllById(teacherIds).stream()
                        .collect(Collectors.toMap(Teacher::getId, Teacher::getShortName)),
                disciplines.findAllById(disciplineIds).stream()
                        .collect(Collectors.toMap(Discipline::getId, Discipline::getShortName)),
                shiftWindows.load(),
                preferences.forSemester(semesterId));
    }

    public static RoomFact roomFact(Room room) {
        return new RoomFact(room.getId(), room.getName(), room.getType(), room.getCapacity(), room.getMaxParallel());
    }

    private Map<UUID, GroupFact> groupFacts(Set<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<Integer>> numbers = new HashMap<>();
        Map<UUID, Map<Integer, Integer>> sizes = new HashMap<>();
        for (Subgroup subgroup : subgroups.findAllByGroupIdIn(groupIds)) {
            numbers.computeIfAbsent(subgroup.getGroupId(), id -> new HashSet<>()).add(subgroup.getNumber());
            if (subgroup.getSize() != null) {
                sizes.computeIfAbsent(subgroup.getGroupId(), id -> new HashMap<>())
                        .put(subgroup.getNumber(), subgroup.getSize());
            }
        }
        return groups.findAllById(groupIds).stream().collect(Collectors.toMap(StudentGroup::getId,
                group -> new GroupFact(group.getId(), group.getCode(), group.getCourse(), group.getSize(),
                        group.getShift(), numbers.getOrDefault(group.getId(), Set.of()),
                        sizes.getOrDefault(group.getId(), Map.of()))));
    }

    private static Set<UUID> ids(Collection<SlotLesson> lessons, Function<SlotLesson, UUID> id) {
        return lessons.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
