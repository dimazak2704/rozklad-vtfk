package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.ArrayList;
import java.util.Arrays;
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
import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.timetable.TeacherRooms;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.internal.conflict.SlotPreference;
import ua.vtc.rozklad.core.timetable.internal.conflict.TeacherSlotPreferences;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository.LoadRow;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository;
import ua.vtc.rozklad.core.timetable.internal.support.PreferenceReadRepository;
import ua.vtc.rozklad.core.timetable.internal.support.TeacherRoomReader;

/**
 * Builds the {@link ProblemSnapshot} of CONTRACTS.md §7 for the groups in scope: units from their
 * load items (pinned when the base version pins them), every lesson of the base version for groups
 * outside the scope as pinned units (the solver works around them), teachers with UNAVAILABLE /
 * UNWANTED slots and the daily limit, rooms, groups with subgroups and shift windows. Unknown sizes and
 * capacities are sent as 0 and {@link Integer#MAX_VALUE}, so the solver never invents a capacity violation.
 *
 * <p>Base lessons of the scope are matched to load units ({@link #match}): by load item, else by
 * discipline and teacher, and only then by discipline alone. A matched unit
 * <ul>
 *   <li>is taught by the base lesson's teacher (B3: the load keeps one teacher per group × discipline ×
 *       subgroup, the timetable may have two, and generation must not swap them silently);</li>
 *   <li>starts where the base lesson stands ({@code initial}): the solver improves the base instead of
 *       rebuilding a nearly full shift from scratch, and measures stability against it.</li>
 * </ul>
 * No room type is derived from the base: a required room type is a hard rule, and keeping PE in the gym is
 * the scheduler's soft rule {@code specialRooms}, read from the base rooms in {@code initial}/{@code pinned}.
 * Each free unit names its teacher's usual room ({@code teacherRoomId}, {@link TeacherRooms#forDiscipline})
 * in the published version of the semester, or in the base version when nothing is published; free units
 * of a split item carry a {@code siblingKey} shared by the subgroup copies of one weekly occurrence. Every group
 * carries the college's Friday limit and every teacher the daily limit (settings).
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
            .thenComparing(TemplateLesson::getId, Comparator.nullsLast(Comparator.naturalOrder()));

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
    private final TimetableVersionRepository versions;
    private final TeacherRoomReader teacherRooms;

    ProblemSnapshotBuilder(StudentGroupRepository groups, SubgroupRepository subgroups, RoomRepository rooms,
                           TeacherRepository teachers, DisciplineRepository disciplines,
                           TemplateLessonRepository templateLessons, LoadReadRepository loads,
                           PreferenceReadRepository preferences, CalendarQueries calendar,
                           SettingsService settings, TimetableVersionRepository versions,
                           TeacherRoomReader teacherRooms) {
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
        this.versions = versions;
        this.teacherRooms = teacherRooms;
    }

    Built build(UUID semesterId, Collection<StudentGroup> scope, TimetableVersion base) {
        SystemSettings current = settings.current();
        Map<UUID, StudentGroup> groupsById = new HashMap<>(scope.stream()
                .collect(Collectors.toMap(StudentGroup::getId, Function.identity())));
        Set<UUID> scopeIds = Set.copyOf(groupsById.keySet());
        Map<UUID, Set<Integer>> storedSubgroups = storedSubgroups(scopeIds);

        List<PlanUnit> loadUnits = loadUnits(semesterId, scopeIds, storedSubgroups, groupsById,
                current.hoursPerPair());
        if (loadUnits.isEmpty()) {
            throw new BusinessRuleException(NO_LOAD,
                    "Для вибраних груп немає навантаження в цьому семестрі: заповніть навантаження перед генерацією.");
        }
        List<TemplateLesson> baseLessons = base == null ? List.of() : templateLessons.findAllByVersionId(base.getId());
        Map<UUID, RoomType> requiredTypes = loads.requiredRoomTypes(baseLessons.stream()
                .map(TemplateLesson::getLoadItemId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<Room> catalogRooms = rooms.findAll();

        // потоки зберігаються як у базовій версії: планувальник їх не моделює (див. PlanUnit.toStreamFollowerUnit)
        List<TemplateLesson> pinnedInScope = baseLessons.stream()
                .filter(lesson -> scopeIds.contains(lesson.getGroupId())
                        && (lesson.isPinned() || lesson.getStreamId() != null))
                .sorted(LESSON_ORDER)
                .toList();
        List<TemplateLesson> freeInScope = baseLessons.stream()
                .filter(lesson -> scopeIds.contains(lesson.getGroupId()) && !lesson.isPinned()
                        && lesson.getStreamId() == null)
                .sorted(LESSON_ORDER)
                .toList();
        List<PlanUnit> units = new ArrayList<>(applyBase(loadUnits, pinnedInScope, freeInScope, requiredTypes));
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
        TeacherRooms usualRooms = teacherRooms.ofVersion(versions.findPublished(semesterId)
                .map(TimetableVersion::getId).orElse(base == null ? null : base.getId()));
        List<ProblemSnapshot.Room> snapshotRooms = snapshotRooms(catalogRooms, units);
        // неактивна аудиторія не потрапляє в знімок: на неї не посилаємося
        Set<UUID> sentRooms = snapshotRooms.stream().map(ProblemSnapshot.Room::id).collect(Collectors.toSet());
        Map<UUID, Long> cohortsOfItem = cohortsOfItem(loadUnits);
        Set<PlanUnit.StreamSlot> streamLeaders = new HashSet<>();
        ProblemSnapshot snapshot = new ProblemSnapshot(timeslots(windows), shiftWindowsOf(windows),
                snapshotGroups(groupsById.values(), storedSubgroups, units, current.fridayMaxPairs()), snapshotRooms,
                snapshotTeachers(units, preferences.forSemester(semesterId), current.teacherMaxPairsPerDay()),
                units.stream().map(unit -> {
                    PlanUnit.StreamSlot stream = unit.streamSlot();
                    return stream == null || streamLeaders.add(stream)
                            ? unit.toSnapshotUnit(usualRooms.forDiscipline(unit.teacherId(), unit.disciplineId())
                                    .filter(sentRooms::contains).orElse(null), unit.initial(),
                                    siblingKey(unit, cohortsOfItem))
                            : unit.toStreamFollowerUnit();
                }).toList());
        return new Built(snapshot, new GenerationPlan(units));
    }

    private List<PlanUnit> loadUnits(UUID semesterId, Set<UUID> scopeIds, Map<UUID, Set<Integer>> storedSubgroups,
                                     Map<UUID, StudentGroup> groupsById, int hoursPerPair) {
        List<PlanUnit> units = new ArrayList<>();
        for (LoadRow item : loads.forGroups(semesterId, scopeIds)) {
            try {
                units.addAll(LoadUnitExpander.expand(item, storedSubgroups.getOrDefault(item.groupId(), Set.of()),
                        hoursPerPair));
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

    /**
     * Load units of the scope against its base lessons: pinned (or stream) lessons pin their unit, the
     * others give their unit a warm start; a matched unit takes the lesson's teacher; an unmatched pinned
     * lesson stays as its own unit. The load's room type is kept as it is: nothing is derived from the base.
     */
    static List<PlanUnit> applyBase(List<PlanUnit> loadUnits, List<TemplateLesson> pinnedLessons,
                                    List<TemplateLesson> freeLessons, Map<UUID, RoomType> requiredTypes) {
        Matching matching = match(loadUnits, pinnedLessons, freeLessons);
        List<PlanUnit> result = new ArrayList<>(loadUnits.size());
        for (int index = 0; index < loadUnits.size(); index++) {
            PlanUnit unit = loadUnits.get(index);
            TemplateLesson pinnedBy = matching.pinnedBy().get(index);
            TemplateLesson startedBy = matching.startedBy().get(index);
            TemplateLesson lesson = pinnedBy != null ? pinnedBy : startedBy;
            if (lesson != null) {
                if (lesson.getTeacherId() != null && !lesson.getTeacherId().equals(unit.teacherId())) {
                    unit = unit.withTeacher(lesson.getTeacherId());
                }
                unit = pinnedBy != null
                        ? unit.pinnedAt(slotOf(lesson), lesson.getId(), lesson.getStreamId(), lesson.isOnline(),
                                lesson.isPinned())
                        : unit.startingAt(slotOf(lesson));
            }
            result.add(unit);
        }
        matching.unmatchedPinned().forEach(lesson -> result.add(pinnedUnit(lesson, requiredTypeOf(lesson,
                requiredTypes))));
        return result;
    }

    /**
     * Matches base lessons to load units, each used once. Exact matches first (by load item, else by
     * discipline and teacher) for pinned and then for free lessons, so a relaxed match cannot take a unit
     * another lesson matches exactly; then the rest by discipline alone (the base lesson's teacher differs
     * from the one the load names).
     */
    static Matching match(List<PlanUnit> units, List<TemplateLesson> pinnedLessons, List<TemplateLesson> freeLessons) {
        int[] pinnedTo = new int[pinnedLessons.size()];
        int[] freeTo = new int[freeLessons.size()];
        Arrays.fill(pinnedTo, -1);
        Arrays.fill(freeTo, -1);
        boolean[] used = new boolean[units.size()];
        for (boolean sameTeacher : new boolean[] {true, false}) {
            assign(units, used, pinnedLessons, pinnedTo, sameTeacher);
            assign(units, used, freeLessons, freeTo, sameTeacher);
        }
        Map<Integer, TemplateLesson> pinnedBy = new HashMap<>();
        List<TemplateLesson> unmatchedPinned = new ArrayList<>();
        for (int i = 0; i < pinnedLessons.size(); i++) {
            if (pinnedTo[i] >= 0) {
                pinnedBy.put(pinnedTo[i], pinnedLessons.get(i));
            } else {
                unmatchedPinned.add(pinnedLessons.get(i));
            }
        }
        Map<Integer, TemplateLesson> startedBy = new HashMap<>();
        for (int i = 0; i < freeLessons.size(); i++) {
            if (freeTo[i] >= 0) {
                startedBy.put(freeTo[i], freeLessons.get(i));
            }
        }
        return new Matching(pinnedBy, startedBy, unmatchedPinned);
    }

    private static void assign(List<PlanUnit> units, boolean[] used, List<TemplateLesson> lessons, int[] matchedTo,
                               boolean sameTeacher) {
        for (int i = 0; i < lessons.size(); i++) {
            if (matchedTo[i] >= 0) {
                continue;
            }
            TemplateLesson lesson = lessons.get(i);
            UnitWeight weight = weightOf(lesson);
            for (int index = 0; index < units.size(); index++) {
                if (!used[index] && matches(units.get(index), lesson, weight, sameTeacher)) {
                    used[index] = true;
                    matchedTo[i] = index;
                    break;
                }
            }
        }
    }

    /** Each free unit starts at the slot of one matching base lesson of its group (each lesson used once). */
    static Map<String, ProblemSnapshot.PinnedSlot> warmStarts(List<PlanUnit> units, List<TemplateLesson> baseLessons) {
        Map<String, ProblemSnapshot.PinnedSlot> starts = new HashMap<>();
        match(units, List.of(), baseLessons).startedBy()
                .forEach((index, lesson) -> starts.put(units.get(index).unitId(), slotOf(lesson)));
        return starts;
    }

    private static boolean matches(PlanUnit unit, TemplateLesson lesson, UnitWeight weight, boolean sameTeacher) {
        if (unit.pinned() != null || unit.weight() != weight || !unit.groupId().equals(lesson.getGroupId())
                || unit.subgroup() != lesson.getSubgroupNumber()) {
            return false;
        }
        if (lesson.getLoadItemId() != null) {
            return lesson.getLoadItemId().equals(unit.loadItemId());
        }
        return unit.disciplineId().equals(lesson.getDisciplineId())
                && (!sameTeacher || Objects.equals(unit.teacherId(), lesson.getTeacherId()));
    }

    private static PlanUnit pinnedUnit(TemplateLesson lesson, RoomType requiredType) {
        return new PlanUnit("pin:" + lesson.getId(), lesson.getLoadItemId(), lesson.getGroupId(),
                lesson.getSubgroupNumber(), lesson.getDisciplineId(), lesson.getTeacherId(), weightOf(lesson),
                lesson.getLessonKind(), requiredType, null, lesson.getStreamId(), lesson.isOnline(), slotOf(lesson),
                lesson.getId(), null, lesson.isPinned());
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

    /** Subgroups (cohorts) of each load item among its units. */
    private static Map<UUID, Long> cohortsOfItem(List<PlanUnit> loadUnits) {
        return loadUnits.stream().filter(unit -> unit.loadItemId() != null && unit.subgroup() > 0)
                .collect(Collectors.groupingBy(PlanUnit::loadItemId,
                        Collectors.mapping(PlanUnit::subgroup, Collectors.collectingAndThen(Collectors.toSet(),
                                set -> (long) set.size()))));
    }

    /**
     * {@code loadItemId:suffix} for a free unit of a split item with two or more subgroups (the subgroup copies
     * of one weekly occurrence share it), otherwise {@code null}.
     */
    static String siblingKey(PlanUnit unit, Map<UUID, Long> cohortsOfItem) {
        if (unit.pinned() != null || unit.loadItemId() == null || unit.subgroup() == 0
                || cohortsOfItem.getOrDefault(unit.loadItemId(), 0L) < 2) {
            return null;
        }
        return unit.loadItemId() + ":" + LoadUnitExpander.occurrenceOf(unit.unitId());
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

    /** Every group gets the college's Friday limit (setting {@code generation.fridayMaxPairs}, a hard rule). */
    private static List<ProblemSnapshot.Group> snapshotGroups(Collection<StudentGroup> groups,
                                                              Map<UUID, Set<Integer>> stored, List<PlanUnit> units,
                                                              int fridayMaxPairs) {
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
                            List.copyOf(numbers), fridayMaxPairs);
                })
                .toList();
    }

    /** Active rooms plus rooms that pinned lessons already use. */
    private static List<ProblemSnapshot.Room> snapshotRooms(List<Room> catalogRooms, List<PlanUnit> units) {
        Set<UUID> pinnedRooms = units.stream().filter(unit -> unit.pinned() != null)
                .map(unit -> unit.pinned().roomId()).filter(Objects::nonNull).collect(Collectors.toSet());
        return catalogRooms.stream()
                .filter(room -> room.isActive() || pinnedRooms.contains(room.getId()))
                .sorted(Comparator.comparing(Room::getName))
                .map(room -> new ProblemSnapshot.Room(room.getId(), room.getName(), room.getType(),
                        room.getCapacity() == null ? UNKNOWN_CAPACITY : room.getCapacity(), room.getMaxParallel()))
                .toList();
    }

    /** Teachers of the units with their slot preferences and the college's daily pair limit. */
    private List<ProblemSnapshot.Teacher> snapshotTeachers(List<PlanUnit> units, TeacherSlotPreferences slots,
                                                           int maxPairsPerDay) {
        Set<UUID> teacherIds = units.stream().map(PlanUnit::teacherId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        return teachers.findAllById(teacherIds).stream()
                .sorted(Comparator.comparing(Teacher::getShortName))
                .map(teacher -> new ProblemSnapshot.Teacher(teacher.getId(), teacher.getShortName(),
                        timeslots(slots.slots(teacher.getId(), SlotPreference.UNAVAILABLE)),
                        timeslots(slots.slots(teacher.getId(), SlotPreference.UNWANTED)), maxPairsPerDay))
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

    record Built(ProblemSnapshot snapshot, GenerationPlan plan) {
    }

    /**
     * Base lessons matched to load units (keys are unit indexes).
     *
     * @param pinnedBy        unit → the pinned (or stream) lesson that pins it
     * @param startedBy       unit → the free lesson it starts at
     * @param unmatchedPinned pinned lessons without a unit (they stay as their own units)
     */
    record Matching(Map<Integer, TemplateLesson> pinnedBy, Map<Integer, TemplateLesson> startedBy,
                    List<TemplateLesson> unmatchedPinned) {
    }
}
