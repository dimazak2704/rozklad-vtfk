package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.ShiftWindows;
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
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.internal.conflict.SlotPreference;
import ua.vtc.rozklad.core.timetable.internal.conflict.TeacherSlotPreferences;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository.LoadRow;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository;
import ua.vtc.rozklad.core.timetable.internal.support.PreferenceReadRepository;

/**
 * Builds the {@link ProblemSnapshot} of CONTRACTS.md §7 for the groups in scope: units from their
 * load items (pinned when the base version pins them), every lesson of the base version for groups
 * outside the scope as pinned units (the solver works around them), teachers with UNAVAILABLE /
 * UNWANTED slots, rooms, groups with subgroups and shift windows. Unknown sizes and capacities are
 * sent as 0 and {@link Integer#MAX_VALUE}, so the solver never invents a capacity violation.
 */
@Component
class ProblemSnapshotBuilder {

    static final String NO_LOAD = "GENERATION_NO_LOAD";
    static final String SPLIT_WITHOUT_SUBGROUPS = "GENERATION_SPLIT_WITHOUT_SUBGROUPS";
    static final int UNKNOWN_SIZE = 0;
    static final int UNKNOWN_CAPACITY = Integer.MAX_VALUE;

    private static final Comparator<TemplateLesson> LESSON_ORDER = Comparator.comparingInt(TemplateLesson::getWeekday)
            .thenComparingInt(TemplateLesson::getPairNumber)
            .thenComparing(TemplateLesson::getGroupId)
            .thenComparingInt(TemplateLesson::getSubgroupNumber)
            .thenComparing(TemplateLesson::getId);

    private final StudentGroupRepository groups;
    private final SubgroupRepository subgroups;
    private final RoomRepository rooms;
    private final TeacherRepository teachers;
    private final DisciplineRepository disciplines;
    private final TemplateLessonRepository templateLessons;
    private final LoadReadRepository loads;
    private final PreferenceReadRepository preferences;
    private final CalendarQueries calendar;
    private final SettingsService settings;

    ProblemSnapshotBuilder(StudentGroupRepository groups, SubgroupRepository subgroups, RoomRepository rooms,
                           TeacherRepository teachers, DisciplineRepository disciplines,
                           TemplateLessonRepository templateLessons, LoadReadRepository loads,
                           PreferenceReadRepository preferences, CalendarQueries calendar,
                           SettingsService settings) {
        this.groups = groups;
        this.subgroups = subgroups;
        this.rooms = rooms;
        this.teachers = teachers;
        this.disciplines = disciplines;
        this.templateLessons = templateLessons;
        this.loads = loads;
        this.preferences = preferences;
        this.calendar = calendar;
        this.settings = settings;
    }

    Built build(UUID semesterId, Collection<StudentGroup> scope, TimetableVersion base) {
        Map<UUID, StudentGroup> groupsById = new HashMap<>(scope.stream()
                .collect(Collectors.toMap(StudentGroup::getId, Function.identity())));
        Set<UUID> scopeIds = Set.copyOf(groupsById.keySet());
        Map<UUID, Set<Integer>> storedSubgroups = storedSubgroups(scopeIds);

        List<PlanUnit> units = new ArrayList<>(loadUnits(semesterId, scopeIds, storedSubgroups, groupsById));
        if (units.isEmpty()) {
            throw new BusinessRuleException(NO_LOAD,
                    "Для вибраних груп немає навантаження в цьому семестрі: заповніть навантаження перед генерацією.");
        }
        List<TemplateLesson> baseLessons = base == null ? List.of() : templateLessons.findAllByVersionId(base.getId());
        Map<UUID, RoomType> requiredTypes = loads.requiredRoomTypes(baseLessons.stream()
                .map(TemplateLesson::getLoadItemId).filter(Objects::nonNull).collect(Collectors.toSet()));
        units = pinInScope(units, baseLessons.stream()
                .filter(lesson -> scopeIds.contains(lesson.getGroupId()) && lesson.isPinned())
                .sorted(LESSON_ORDER)
                .toList(), requiredTypes);
        baseLessons.stream()
                .filter(lesson -> !scopeIds.contains(lesson.getGroupId()))
                .sorted(LESSON_ORDER)
                .map(lesson -> pinnedUnit(lesson, requiredTypeOf(lesson, requiredTypes)))
                .forEach(units::add);

        Set<UUID> outsideGroupIds = units.stream().map(PlanUnit::groupId)
                .filter(id -> !groupsById.containsKey(id)).collect(Collectors.toSet());
        groups.findAllById(outsideGroupIds).forEach(group -> groupsById.put(group.getId(), group));
        storedSubgroups.putAll(storedSubgroups(outsideGroupIds));
        ShiftWindows windows = calendar.shiftWindowTable();
        ProblemSnapshot snapshot = new ProblemSnapshot(timeslots(windows), shiftWindowsOf(windows),
                snapshotGroups(groupsById.values(), storedSubgroups, units), snapshotRooms(units),
                snapshotTeachers(units, preferences.forSemester(semesterId)),
                units.stream().map(unit -> unit.toSnapshotUnit(homeRoom(groupsById.get(unit.groupId())))).toList());
        return new Built(snapshot, new GenerationPlan(units));
    }

    private List<PlanUnit> loadUnits(UUID semesterId, Set<UUID> scopeIds, Map<UUID, Set<Integer>> storedSubgroups,
                                     Map<UUID, StudentGroup> groupsById) {
        int hours = settings.hoursPerPair();
        List<PlanUnit> units = new ArrayList<>();
        for (LoadRow item : loads.forGroups(semesterId, scopeIds)) {
            try {
                units.addAll(LoadUnitExpander.expand(item, storedSubgroups.getOrDefault(item.groupId(), Set.of()),
                        hours));
            } catch (LoadUnitExpander.SplitWithoutSubgroupsException splitWithoutSubgroups) {
                String discipline = disciplines.findById(item.disciplineId()).map(Discipline::getShortName)
                        .orElse(item.disciplineId().toString());
                throw new BusinessRuleException(SPLIT_WITHOUT_SUBGROUPS, ("Навантаження групи %s з дисципліни %s "
                        + "поділене на підгрупи, але підгруп немає: вкажіть викладачів підгруп або створіть підгрупи.")
                        .formatted(groupsById.get(item.groupId()).getCode(), discipline));
            }
        }
        return units;
    }

    /** Pins units of in-scope groups that the base version pins; an unmatched pinned lesson stays as its own unit. */
    private static List<PlanUnit> pinInScope(List<PlanUnit> units, List<TemplateLesson> pinnedLessons,
                                             Map<UUID, RoomType> requiredTypes) {
        List<PlanUnit> result = new ArrayList<>(units);
        for (TemplateLesson lesson : pinnedLessons) {
            UnitWeight weight = weightOf(lesson);
            ProblemSnapshot.PinnedSlot slot = slotOf(lesson);
            int index = -1;
            for (int i = 0; i < result.size() && index < 0; i++) {
                if (matches(result.get(i), lesson, weight)) {
                    index = i;
                }
            }
            if (index >= 0) {
                result.set(index, result.get(index).pinnedAt(slot, lesson.getId()));
            } else {
                result.add(pinnedUnit(lesson, requiredTypeOf(lesson, requiredTypes)));
            }
        }
        return result;
    }

    private static boolean matches(PlanUnit unit, TemplateLesson lesson, UnitWeight weight) {
        if (unit.pinned() != null || unit.weight() != weight || !unit.groupId().equals(lesson.getGroupId())
                || unit.subgroup() != lesson.getSubgroupNumber()) {
            return false;
        }
        if (lesson.getLoadItemId() != null) {
            return lesson.getLoadItemId().equals(unit.loadItemId());
        }
        return unit.disciplineId().equals(lesson.getDisciplineId())
                && Objects.equals(unit.teacherId(), lesson.getTeacherId());
    }

    private static PlanUnit pinnedUnit(TemplateLesson lesson, RoomType requiredType) {
        return new PlanUnit("pin:" + lesson.getId(), lesson.getLoadItemId(), lesson.getGroupId(),
                lesson.getSubgroupNumber(), lesson.getDisciplineId(), lesson.getTeacherId(), weightOf(lesson),
                lesson.getLessonKind(), requiredType, null, lesson.getStreamId(), lesson.isOnline(), slotOf(lesson),
                lesson.getId());
    }

    private static RoomType requiredTypeOf(TemplateLesson lesson, Map<UUID, RoomType> requiredTypes) {
        return lesson.getLoadItemId() == null ? null : requiredTypes.get(lesson.getLoadItemId());
    }

    private static UnitWeight weightOf(TemplateLesson lesson) {
        return lesson.getParity() == Parity.BOTH ? UnitWeight.WHOLE : UnitWeight.HALF;
    }

    private static ProblemSnapshot.PinnedSlot slotOf(TemplateLesson lesson) {
        return new ProblemSnapshot.PinnedSlot(lesson.getWeekday(), lesson.getPairNumber(), lesson.getParity(),
                lesson.getRoomId());
    }

    private Map<UUID, Set<Integer>> storedSubgroups(Collection<UUID> groupIds) {
        Map<UUID, Set<Integer>> numbers = new HashMap<>();
        if (!groupIds.isEmpty()) {
            for (Subgroup subgroup : subgroups.findAllByGroupIdIn(groupIds)) {
                numbers.computeIfAbsent(subgroup.getGroupId(), id -> new TreeSet<>()).add(subgroup.getNumber());
            }
        }
        return numbers;
    }

    private static List<ProblemSnapshot.Group> snapshotGroups(Collection<StudentGroup> groups,
                                                              Map<UUID, Set<Integer>> stored, List<PlanUnit> units) {
        Map<UUID, Set<Integer>> used = new HashMap<>();
        units.stream().filter(unit -> unit.subgroup() > 0)
                .forEach(unit -> used.computeIfAbsent(unit.groupId(), id -> new TreeSet<>()).add(unit.subgroup()));
        return groups.stream()
                .sorted(Comparator.comparingInt(StudentGroup::getCourse).thenComparing(StudentGroup::getCode))
                .map(group -> {
                    Set<Integer> numbers = new TreeSet<>(stored.getOrDefault(group.getId(), Set.of()));
                    numbers.addAll(used.getOrDefault(group.getId(), Set.of()));
                    return new ProblemSnapshot.Group(group.getId(), group.getCode(),
                            group.getSize() == null ? UNKNOWN_SIZE : group.getSize(), group.getShift(),
                            List.copyOf(numbers));
                })
                .toList();
    }

    /** Active rooms plus rooms that pinned lessons already use. */
    private List<ProblemSnapshot.Room> snapshotRooms(List<PlanUnit> units) {
        Set<UUID> pinnedRooms = units.stream().filter(unit -> unit.pinned() != null)
                .map(unit -> unit.pinned().roomId()).filter(Objects::nonNull).collect(Collectors.toSet());
        return rooms.findAll().stream()
                .filter(room -> room.isActive() || pinnedRooms.contains(room.getId()))
                .sorted(Comparator.comparing(Room::getName))
                .map(room -> new ProblemSnapshot.Room(room.getId(), room.getName(), room.getType(),
                        room.getCapacity() == null ? UNKNOWN_CAPACITY : room.getCapacity(), room.getMaxParallel()))
                .toList();
    }

    private List<ProblemSnapshot.Teacher> snapshotTeachers(List<PlanUnit> units, TeacherSlotPreferences slots) {
        Set<UUID> teacherIds = units.stream().map(PlanUnit::teacherId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        return teachers.findAllById(teacherIds).stream()
                .sorted(Comparator.comparing(Teacher::getShortName))
                .map(teacher -> new ProblemSnapshot.Teacher(teacher.getId(), teacher.getShortName(),
                        timeslots(slots.slots(teacher.getId(), SlotPreference.UNAVAILABLE)),
                        timeslots(slots.slots(teacher.getId(), SlotPreference.UNWANTED))))
                .toList();
    }

    private static List<ProblemSnapshot.Timeslot> timeslots(List<TeacherSlotPreferences.Slot> slots) {
        return slots.stream().map(slot -> new ProblemSnapshot.Timeslot(slot.weekday(), slot.pair())).toList();
    }

    /** Every weekday × pair that some shift may use. */
    private static List<ProblemSnapshot.Timeslot> timeslots(ShiftWindows windows) {
        Set<Integer> keys = new TreeSet<>();
        windows.rows().forEach(row -> {
            for (int pair = row.firstPair(); pair <= row.lastPair(); pair++) {
                keys.add(row.weekday() * 10 + pair);
            }
        });
        return keys.stream().map(key -> new ProblemSnapshot.Timeslot(key / 10, key % 10)).toList();
    }

    private static List<ProblemSnapshot.ShiftWindow> shiftWindowsOf(ShiftWindows windows) {
        return windows.rows().stream()
                .map(row -> new ProblemSnapshot.ShiftWindow(row.shift(), row.weekday(), row.firstPair(),
                        row.lastPair()))
                .toList();
    }

    private static UUID homeRoom(StudentGroup group) {
        return group == null ? null : group.getHomeRoomId();
    }

    record Built(ProblemSnapshot snapshot, GenerationPlan plan) {
    }
}
