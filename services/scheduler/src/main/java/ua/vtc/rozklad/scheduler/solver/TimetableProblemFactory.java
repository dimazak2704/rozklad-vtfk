package ua.vtc.rozklad.scheduler.solver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.ShiftWindow;
import ua.vtc.rozklad.scheduler.solver.domain.StudentGroup;
import ua.vtc.rozklad.scheduler.solver.domain.Teacher;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.ConstraintWeights;

/**
 * Builds the planning problem from the snapshot sent by core.
 *
 * <p>Value ranges per unit: timeslots of the group's shift window where the teacher is not
 * UNAVAILABLE; rooms of the required type that fit the group (special-purpose rooms — sports
 * hall and ground — only for units that require them, name them as preferred, teacher's or home
 * room, or stood there in the base version); parity
 * BOTH for WHOLE units, NUMERATOR or DENOMINATOR for HALF units. When a filter leaves nothing, the
 * wider range is used and the matching hard constraint reports the violation. A desired room
 * that is not in the snapshot is ignored with a warning. An online pinned unit (DOMAIN §5) stands
 * in {@link Room#NONE} and keeps its room only for the result.
 *
 * <p>A unit with an {@code initial} placement (where it stands in the base version) starts there when
 * the slot and parity are inside its value ranges; the construction heuristic places only the units
 * that have no start, so a feasible base is improved, never rebuilt worse. Local search may still
 * change anything that is not pinned. The base placement is also kept on the unit for the stability
 * rules, even when it is outside the value ranges.
 *
 * <p>Rooms are warm-started. A started unit keeps its base room when it is compatible; a started unit
 * without one (the base lesson had no room) takes a compatible room that is free at its start slot —
 * the desired room first — so the run never starts with a room clash the base did not have (B2).
 * Every other unit starts in its desired room (preferred room of the load item, else the teacher's
 * usual room, else the group's home room of an older snapshot) or in the least used compatible room.
 *
 * <p>A load item whose base lessons with a room all sit in special-purpose rooms (PE in the sports hall or on the
 * ground; data-driven, never by discipline name) gives its free units those room types as
 * {@link LessonUnit#getBaseRoomTypes()}: the units start there, and the soft rule {@code specialRooms} penalizes
 * any other room. An item without any base lesson in a room (a run from scratch) gets both special-purpose types
 * when its teacher's usual room or its preferred room is one (the PE teacher's room is the hall). Ordinary
 * compatible rooms stay in the range, so a full or closed hall costs soft points instead of making the run
 * infeasible, and weight 0 switches the preference off. Core sends no derived room type.
 *
 * <p>Each group gets the Friday limit of the snapshot (hard rule «Забагато пар у пʼятницю»; absent = none).
 * Each teacher gets the daily limit of the snapshot and a baseline of teaching days per kind of
 * week: the weekdays of the teacher's base and pinned lessons in that week, but at least the days the
 * teacher's load needs at the daily limit (4 pairs when there is none). Sibling keys are kept only on
 * free units of one group with distinct subgroups; any other use is dropped with a warning. A key whose copies
 * cannot all be parallel by the data is marked soft ({@link LessonUnit#hasSoftSiblingRule()}), also with a warning.
 */
public final class TimetableProblemFactory {

    private static final Logger log = LoggerFactory.getLogger(TimetableProblemFactory.class);
    private static final Set<RoomType> SPECIAL_PURPOSE = EnumSet.of(RoomType.GYM, RoomType.SPORTS_GROUND);
    private static final List<Parity> WHOLE_PARITIES = List.of(Parity.BOTH);
    private static final List<Parity> HALF_PARITIES = List.of(Parity.NUMERATOR, Parity.DENOMINATOR);

    private final int disciplineRepeatWeight;

    public TimetableProblemFactory(int disciplineRepeatWeight) {
        this.disciplineRepeatWeight = disciplineRepeatWeight;
    }

    /** @throws InvalidProblemException when the snapshot is inconsistent */
    public Timetable create(ProblemSnapshot snapshot, GenerationWeights weights) {
        List<String> errors = ProblemSnapshotValidator.validate(snapshot);
        if (!errors.isEmpty()) {
            throw new InvalidProblemException(errors);
        }
        List<Timeslot> timeslots = snapshot.timeslots().stream()
                .map(slot -> new Timeslot(slot.weekday(), slot.pair()))
                .distinct().sorted().toList();
        Map<UUID, Room> rooms = snapshot.rooms().stream()
                .map(room -> new Room(room.id(), room.name(), room.type(), room.capacity(), room.maxParallel()))
                .collect(Collectors.toMap(Room::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<UUID, List<ProblemSnapshot.Unit>> unitsByTeacher = snapshot.units().stream()
                .filter(unit -> unit.teacherId() != null)
                .collect(Collectors.groupingBy(ProblemSnapshot.Unit::teacherId));
        Map<UUID, Teacher> teachers = snapshot.teachers().stream()
                .map(teacher -> teacher(teacher, unitsByTeacher.getOrDefault(teacher.id(), List.of())))
                .collect(Collectors.toMap(Teacher::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Integer, ShiftWindow> windows = shiftWindows(snapshot.shiftWindows());
        Map<UUID, StudentGroup> groups = snapshot.groups().stream()
                .map(group -> new StudentGroup(group.id(), group.code(), group.size(), group.shift(),
                        group.subgroups(), windows.get(group.shift()), group.fridayMaxPairs()))
                .collect(Collectors.toMap(StudentGroup::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        Set<String> siblingKeys = siblingKeys(snapshot.units());
        Map<UUID, Set<RoomType>> sportItems = specialPurposeItems(snapshot.units(), rooms);
        List<UnitPlan> plans = snapshot.units().stream()
                .map(unit -> plan(unit, timeslots, rooms, teachers, groups, siblingKeys, sportItems))
                .toList();
        List<LessonUnit> units = new RoomWarmStart(plans).buildUnits(softSiblingKeys(plans));
        return new Timetable(timeslots, List.copyOf(rooms.values()), List.copyOf(teachers.values()),
                List.copyOf(groups.values()), units, ConstraintWeights.of(weights, disciplineRepeatWeight));
    }

    private static Teacher teacher(ProblemSnapshot.Teacher teacher, List<ProblemSnapshot.Unit> units) {
        return new Teacher(teacher.id(), teacher.name(), slots(teacher.unavailable()), slots(teacher.unwanted()),
                teacher.maxPairsPerDay(), baseDays(units, teacher.maxPairsPerDay()));
    }

    /**
     * Teaching days per kind of week a teacher may use without the «extra day» penalty: the weekdays of
     * the base and pinned lessons in that week, but at least the days the load needs at the daily limit.
     */
    static int[] baseDays(List<ProblemSnapshot.Unit> units, Integer maxPairsPerDay) {
        List<Set<Integer>> days = List.of(new HashSet<>(), new HashSet<>());
        int whole = 0;
        int halves = 0;
        for (ProblemSnapshot.Unit unit : units) {
            if (unit.weight() == UnitWeight.HALF) {
                halves++;
            } else {
                whole++;
            }
            ProblemSnapshot.PinnedSlot placed = unit.pinned() != null ? unit.pinned() : unit.initial();
            if (placed != null && placed.parity() != null && isValidSlot(placed.weekday(), placed.pair())) {
                for (int week : weekLanes(placed.parity())) {
                    days.get(week).add(placed.weekday());
                }
            }
        }
        int limit = maxPairsPerDay == null ? Teacher.DEFAULT_PAIRS_PER_DAY : maxPairsPerDay;
        // половинки діляться між тижнями: у кожному тижні в середньому половина з них
        int needed = Math.ceilDiv(whole + (halves + 1) / 2, limit);
        return new int[] {Math.max(days.get(0).size(), needed), Math.max(days.get(1).size(), needed)};
    }

    private static int[] weekLanes(Parity parity) {
        return switch (parity) {
            case BOTH -> new int[] {LessonUnit.NUMERATOR_WEEK, LessonUnit.DENOMINATOR_WEEK};
            case NUMERATOR -> new int[] {LessonUnit.NUMERATOR_WEEK};
            case DENOMINATOR -> new int[] {LessonUnit.DENOMINATOR_WEEK};
        };
    }

    private static boolean isValidSlot(int weekday, int pair) {
        return weekday >= 1 && weekday <= Timeslot.MAX_WEEKDAY && pair >= 1 && pair <= Timeslot.MAX_PAIR;
    }

    /**
     * Sibling keys that are safe to use: shared by at least two free units of one group with distinct
     * subgroups. Any other use (another group, the whole group, a repeated subgroup) is a core data
     * error that would only mislead the subgroup rules, so the key is dropped with a warning.
     */
    static Set<String> siblingKeys(List<ProblemSnapshot.Unit> units) {
        Map<String, List<ProblemSnapshot.Unit>> byKey = units.stream()
                .filter(unit -> unit.siblingKey() != null && unit.pinned() == null)
                .collect(Collectors.groupingBy(ProblemSnapshot.Unit::siblingKey));
        Set<String> valid = new HashSet<>();
        byKey.forEach((key, siblings) -> {
            boolean oneGroup = siblings.stream().map(ProblemSnapshot.Unit::groupId).distinct().count() == 1;
            boolean distinctSubgroups = siblings.stream().allMatch(unit -> unit.subgroup() > 0)
                    && siblings.stream().map(ProblemSnapshot.Unit::subgroup).distinct().count() == siblings.size();
            if (siblings.size() >= 2 && oneGroup && distinctSubgroups) {
                valid.add(key);
            } else if (siblings.size() >= 2) {
                log.warn("Sibling key {} joins units of different groups or the same subgroup, ignored", key);
            }
        });
        return valid;
    }

    /**
     * Sibling keys whose copies cannot all stand in parallel, whatever the timetable: one teacher teaches two of
     * three or more copies (A, A, B: B cannot be parallel to both of A's copies, and A's copies cannot be parallel
     * to each other), or the copies share no allowed slot or parity (the teachers' free slots never meet in the
     * group's shift). The hard rule would make such a run infeasible by the data alone, so standing together is
     * soft for every pair of the key. Two copies of one teacher are the ordinary soft case and are not listed.
     */
    private static Set<String> softSiblingKeys(List<UnitPlan> plans) {
        Map<String, List<UnitPlan>> byKey = new LinkedHashMap<>();
        for (UnitPlan plan : plans) {
            if (plan.siblingKey() != null) {
                byKey.computeIfAbsent(plan.siblingKey(), key -> new ArrayList<>()).add(plan);
            }
        }
        Set<String> soft = new HashSet<>();
        byKey.forEach((key, copies) -> {
            List<UUID> teachers = copies.stream().map(copy -> copy.unit().teacherId()).filter(Objects::nonNull)
                    .toList();
            if (teachers.stream().distinct().count() < teachers.size()) {
                if (copies.size() > 2) {
                    soft.add(key);
                    log.warn("Sibling key {}: one teacher teaches two of its {} subgroup copies, so they cannot all "
                            + "be parallel; together is a soft rule for this key", key, copies.size());
                }
                return;
            }
            Set<Timeslot> slots = new HashSet<>(copies.getFirst().timeslotRange());
            Set<Parity> parities = EnumSet.copyOf(copies.getFirst().parityRange());
            for (UnitPlan copy : copies.subList(1, copies.size())) {
                slots.retainAll(copy.timeslotRange());
                parities.retainAll(copy.parityRange());
            }
            if (slots.isEmpty() || parities.isEmpty()) {
                soft.add(key);
                log.warn("Sibling key {}: its subgroup copies share no allowed slot or week (the teachers' free slots "
                        + "do not meet in the shift), so they cannot be parallel; together is a soft rule for this key",
                        key);
            }
        });
        return soft;
    }

    private static Set<Timeslot> slots(List<ProblemSnapshot.Timeslot> slots) {
        return slots.stream().map(slot -> new Timeslot(slot.weekday(), slot.pair())).collect(Collectors.toSet());
    }

    private static Map<Integer, ShiftWindow> shiftWindows(List<ProblemSnapshot.ShiftWindow> windows) {
        Map<Integer, List<ShiftWindow.DayRange>> byShift = new HashMap<>();
        for (ProblemSnapshot.ShiftWindow window : windows) {
            byShift.computeIfAbsent(window.shift(), shift -> new ArrayList<>())
                    .add(new ShiftWindow.DayRange(window.weekday(), window.firstPair(), window.lastPair()));
        }
        Map<Integer, ShiftWindow> result = new HashMap<>();
        byShift.forEach((shift, days) -> result.put(shift, new ShiftWindow(days)));
        return result;
    }

    private static UnitPlan plan(ProblemSnapshot.Unit unit, List<Timeslot> timeslots, Map<UUID, Room> rooms,
                                 Map<UUID, Teacher> teachers, Map<UUID, StudentGroup> groups,
                                 Set<String> siblingKeys, Map<UUID, Set<RoomType>> sportItems) {
        StudentGroup group = groups.get(unit.groupId());
        Teacher teacher = unit.teacherId() == null ? null : teachers.get(unit.teacherId());
        if (unit.pinned() != null) {
            ProblemSnapshot.PinnedSlot pin = unit.pinned();
            // онлайн-заняття лише пам'ятає аудиторію (DOMAIN §5): не займає її, місткість і тип не перевіряються
            Room room = pin.roomId() == null || unit.online() ? Room.NONE : rooms.get(pin.roomId());
            return UnitPlan.pinned(unit, group, teacher, new Timeslot(pin.weekday(), pin.pair()), room, pin.parity());
        }
        warnAboutUnknownDesiredRooms(unit, rooms);
        Set<RoomType> baseRoomTypes = unit.requiredRoomType() != null || unit.loadItemId() == null ? Set.of()
                : sportItems.getOrDefault(unit.loadItemId(), Set.of());
        List<Room> roomRange = roomRange(unit, group, rooms.values(), baseRoomTypes);
        // предмет зі спортзалу бажає лише спецаудиторію: «аудиторія викладача» не тягне його в клас
        Room desired = desiredRoom(unit, rooms, baseRoomTypes.isEmpty() ? roomRange
                : roomRange.stream().filter(room -> baseRoomTypes.contains(room.getType())).toList());
        List<Parity> parities = unit.weight() == UnitWeight.HALF ? HALF_PARITIES : WHOLE_PARITIES;
        List<Timeslot> slotRange = timeslotRange(unit, group, teacher, timeslots);
        String siblingKey = siblingKeys.contains(unit.siblingKey()) ? unit.siblingKey() : null;
        UnitPlan plan = UnitPlan.free(unit, group, teacher, slotRange, roomRange, parities, desired, siblingKey,
                baseRoomTypes);
        ProblemSnapshot.PinnedSlot start = unit.initial();
        if (start == null || start.parity() == null || !isValidSlot(start.weekday(), start.pair())) {
            return plan;
        }
        plan = plan.withBase(new Timeslot(start.weekday(), start.pair()), start.parity(), start.roomId());
        // те саме значення, що в діапазоні (ходи розв'язувача працюють з екземплярами діапазону)
        Timeslot slot = slotRange.stream()
                .filter(candidate -> candidate.equals(new Timeslot(start.weekday(), start.pair())))
                .findFirst().orElse(null);
        if (slot == null || !parities.contains(start.parity())) {
            log.debug("Unit {}: base placement {}/{} {} is outside its value range, placed from scratch",
                    unit.unitId(), start.weekday(), start.pair(), start.parity());
            return plan;
        }
        Room startRoom = start.roomId() == null ? null : rooms.get(start.roomId());
        return plan.startingAt(slot, start.parity(), startRoom != null && roomRange.contains(startRoom) ? startRoom
                : null);
    }

    /**
     * The first compatible room of: the load item's preferred room, the teacher's usual room (the room
     * belongs to the teacher, round 7), the group's home room (sent only by cores before round 7).
     */
    static Room desiredRoom(ProblemSnapshot.Unit unit, Map<UUID, Room> rooms, List<Room> roomRange) {
        for (UUID id : new UUID[] {unit.preferredRoomId(), unit.teacherRoomId(), unit.homeRoomId()}) {
            Room room = id == null ? null : rooms.get(id);
            if (room != null && roomRange.contains(room)) {
                return room;
            }
        }
        return null;
    }

    /** A desired room core did not send (deactivated) is a lost preference, not an error. */
    private static void warnAboutUnknownDesiredRooms(ProblemSnapshot.Unit unit, Map<UUID, Room> rooms) {
        for (UUID id : new UUID[] {unit.preferredRoomId(), unit.teacherRoomId(), unit.homeRoomId()}) {
            if (id != null && !rooms.containsKey(id)) {
                log.warn("Unit {}: desired room {} is not in the snapshot (inactive?), ignored", unit.unitId(), id);
            }
        }
    }

    private static List<Timeslot> timeslotRange(ProblemSnapshot.Unit unit, StudentGroup group, Teacher teacher,
                                                List<Timeslot> timeslots) {
        List<Timeslot> inWindow = timeslots.stream().filter(group::allows).toList();
        List<Timeslot> available = inWindow.stream()
                .filter(slot -> teacher == null || !teacher.isUnavailableAt(slot))
                .toList();
        if (available.isEmpty()) {
            log.warn("Unit {}: teacher {} is unavailable in the whole shift window of {}", unit.unitId(),
                    teacher == null ? null : teacher.getId(), group.getCode());
            return inWindow;
        }
        return available;
    }

    /**
     * Load items whose base lessons with a room (free units' {@code initial}, pinned units' pin; online lessons
     * left out) all sit in special-purpose rooms, with those room types. On the real college these are the 60 PE
     * items: 56 only in the hall, 2 only on the ground, 2 in both; no PE lesson stands in a classroom. An item with
     * no base lesson in a room (from scratch) gets both special-purpose types when its free unit's preferred room is
     * one, or when its teacher's usual room is one and its discipline has no trace of an ordinary room: the college
     * keeps PE in the hall or on the ground (owner, 15.11.2026), and from scratch the teacher's room is the only
     * trace of it (all 100 PE units of the real request from scratch have the hall as the teacher's room, no other
     * unit has). The discipline check keeps a PE teacher's theory discipline out of the hall when that discipline is
     * taught in classrooms elsewhere; a discipline that only the PE teacher teaches has no such trace and follows
     * the teacher's room.
     */
    static Map<UUID, Set<RoomType>> specialPurposeItems(List<ProblemSnapshot.Unit> units, Map<UUID, Room> rooms) {
        Map<UUID, Set<RoomType>> types = new HashMap<>();
        Set<UUID> based = new HashSet<>();
        Set<UUID> elsewhere = new HashSet<>();
        for (ProblemSnapshot.Unit unit : units) {
            ProblemSnapshot.PinnedSlot placed = unit.pinned() != null ? unit.pinned() : unit.initial();
            Room room = placed == null || placed.roomId() == null ? null : rooms.get(placed.roomId());
            if (unit.loadItemId() == null || unit.online() || room == null) {
                continue;
            }
            based.add(unit.loadItemId());
            if (SPECIAL_PURPOSE.contains(room.getType())) {
                types.computeIfAbsent(unit.loadItemId(), id -> EnumSet.noneOf(RoomType.class)).add(room.getType());
            } else {
                elsewhere.add(unit.loadItemId());
            }
        }
        elsewhere.forEach(types::remove);
        Set<UUID> ordinary = disciplinesInOrdinaryRooms(units, rooms);
        for (ProblemSnapshot.Unit unit : units) {
            if (unit.loadItemId() != null && unit.pinned() == null && !based.contains(unit.loadItemId())
                    && (isSpecial(unit.preferredRoomId(), rooms) || isSpecial(unit.teacherRoomId(), rooms)
                    && !ordinary.contains(unit.disciplineId()))) {
                types.put(unit.loadItemId(), SPECIAL_PURPOSE);
            }
        }
        return types;
    }

    /**
     * Disciplines with a trace of an ordinary room: a base or pinned lesson in one (online lessons left out), or a
     * unit without such a lesson whose preferred room or teacher's usual room is one.
     */
    private static Set<UUID> disciplinesInOrdinaryRooms(List<ProblemSnapshot.Unit> units, Map<UUID, Room> rooms) {
        Set<UUID> ordinary = new HashSet<>();
        for (ProblemSnapshot.Unit unit : units) {
            ProblemSnapshot.PinnedSlot placed = unit.pinned() != null ? unit.pinned() : unit.initial();
            Room room = placed == null || placed.roomId() == null || unit.online() ? null : rooms.get(placed.roomId());
            boolean trace = room != null ? !SPECIAL_PURPOSE.contains(room.getType())
                    : isOrdinary(unit.preferredRoomId(), rooms) || isOrdinary(unit.teacherRoomId(), rooms);
            if (trace) {
                ordinary.add(unit.disciplineId());
            }
        }
        return ordinary;
    }

    /** The room is a sports hall or ground of the snapshot. */
    private static boolean isSpecial(UUID roomId, Map<UUID, Room> rooms) {
        Room room = roomId == null ? null : rooms.get(roomId);
        return room != null && SPECIAL_PURPOSE.contains(room.getType());
    }

    /** The room is an ordinary room of the snapshot (not a sports hall or ground). */
    private static boolean isOrdinary(UUID roomId, Map<UUID, Room> rooms) {
        Room room = roomId == null ? null : rooms.get(roomId);
        return room != null && !SPECIAL_PURPOSE.contains(room.getType());
    }

    /**
     * Rooms of the required type that fit the group. A unit with base room types gets the special-purpose rooms of
     * those types first and then the ordinary compatible rooms: the soft rule decides, not the range.
     */
    private static List<Room> roomRange(ProblemSnapshot.Unit unit, StudentGroup group, Collection<Room> rooms,
                                        Set<RoomType> baseRoomTypes) {
        List<Room> compatible = rooms.stream()
                .filter(room -> (fitsType(unit, room) || baseRoomTypes.contains(room.getType()))
                        && fitsCapacity(unit, group, room))
                .sorted(Comparator.comparing((Room room) -> !baseRoomTypes.contains(room.getType())))
                .toList();
        if (compatible.isEmpty()) {
            log.warn("Unit {} of {}: no room of type {} fits, every room is allowed", unit.unitId(), group.getCode(),
                    unit.requiredRoomType());
            return List.copyOf(rooms);
        }
        return compatible;
    }

    /**
     * A special-purpose room (sports hall, sports ground) only for a unit that requires it, names it as a
     * desired room or stood there in the base version: a PE lesson of a load item that uses both the hall and
     * the ground (no single derived type) keeps its base room instead of starting in a classroom.
     */
    private static boolean fitsType(ProblemSnapshot.Unit unit, Room room) {
        if (unit.requiredRoomType() != null) {
            return room.getType() == unit.requiredRoomType();
        }
        UUID baseRoom = unit.initial() == null ? null : unit.initial().roomId();
        return !SPECIAL_PURPOSE.contains(room.getType()) || room.getId().equals(unit.preferredRoomId())
                || room.getId().equals(unit.teacherRoomId()) || room.getId().equals(unit.homeRoomId())
                || room.getId().equals(baseRoom);
    }

    private static boolean fitsCapacity(ProblemSnapshot.Unit unit, StudentGroup group, Room room) {
        return unit.subgroup() != 0 || !room.hasKnownCapacity() || !group.hasKnownSize()
                || room.getCapacity() >= group.getSize();
    }

    /** Everything known about a unit before its initial room is chosen. */
    private record UnitPlan(ProblemSnapshot.Unit unit, StudentGroup group, Teacher teacher,
                            List<Timeslot> timeslotRange, List<Room> roomRange, List<Parity> parityRange,
                            Room desiredRoom, String siblingKey, Set<RoomType> baseRoomTypes, Timeslot pinnedSlot,
                            Room pinnedRoom, Parity pinnedParity, Timeslot baseSlot, Parity baseParity,
                            UUID baseRoomId, Timeslot startSlot, Parity startParity, Room startRoom) {

        static UnitPlan pinned(ProblemSnapshot.Unit unit, StudentGroup group, Teacher teacher, Timeslot slot,
                               Room room, Parity parity) {
            return new UnitPlan(unit, group, teacher, List.of(), List.of(), List.of(), null, null, Set.of(), slot,
                    room, parity, null, null, null, null, null, null);
        }

        static UnitPlan free(ProblemSnapshot.Unit unit, StudentGroup group, Teacher teacher,
                             List<Timeslot> timeslotRange, List<Room> roomRange, List<Parity> parityRange,
                             Room desiredRoom, String siblingKey, Set<RoomType> baseRoomTypes) {
            return new UnitPlan(unit, group, teacher, timeslotRange, roomRange, parityRange, desiredRoom, siblingKey,
                    baseRoomTypes, null, null, null, null, null, null, null, null, null);
        }

        /** Remembers the base placement (for the stability rules), whether or not the unit starts there. */
        UnitPlan withBase(Timeslot slot, Parity parity, UUID roomId) {
            return new UnitPlan(unit, group, teacher, timeslotRange, roomRange, parityRange, desiredRoom, siblingKey,
                    baseRoomTypes, null, null, null, slot, parity, roomId, startSlot, startParity, startRoom);
        }

        /** Starts at the base placement; {@code room} {@code null} lets the warm start pick a free room. */
        UnitPlan startingAt(Timeslot slot, Parity parity, Room room) {
            return new UnitPlan(unit, group, teacher, timeslotRange, roomRange, parityRange, desiredRoom, siblingKey,
                    baseRoomTypes, null, null, null, baseSlot, baseParity, baseRoomId, slot, parity, room);
        }

        /** Rooms the warm start tries first: those of the base room types when there are any, else the range. */
        List<Room> preferredRooms() {
            if (baseRoomTypes.isEmpty()) {
                return roomRange;
            }
            List<Room> special = roomRange.stream().filter(room -> baseRoomTypes.contains(room.getType())).toList();
            return special.isEmpty() ? roomRange : special;
        }

        boolean hasStart() {
            return startSlot != null;
        }

        boolean isPinned() {
            return pinnedSlot != null;
        }

        /** Week occurrences the unit will take in a room (BOTH = 2, a half = 1). */
        int occurrences() {
            if (isPinned()) {
                return pinnedParity == Parity.BOTH ? 2 : 1;
            }
            return unit.weight() == UnitWeight.HALF ? 1 : 2;
        }

        LessonUnit build(Room initialRoom, boolean softSiblingRule) {
            LessonUnit.Builder builder = LessonUnit.builder(unit.unitId(), group, unit.disciplineId())
                    .loadItemId(unit.loadItemId())
                    .subgroup(unit.subgroup())
                    .teacher(teacher)
                    .weight(unit.weight())
                    .requiredRoomType(unit.requiredRoomType())
                    .desiredRoom(desiredRoom);
            if (isPinned()) {
                return builder.pinnedAt(pinnedSlot, pinnedRoom, pinnedParity)
                        .keptRoomId(unit.online() && unit.pinned() != null ? unit.pinned().roomId() : null)
                        .build();
            }
            builder.timeslotRange(timeslotRange)
                    .roomRange(roomRange)
                    .parityRange(parityRange)
                    .siblingKey(siblingKey)
                    .softSiblingRule(softSiblingRule)
                    .baseRoomTypes(baseRoomTypes)
                    .initialRoom(initialRoom);
            if (baseSlot != null) {
                builder.base(baseSlot, baseParity, baseRoomId);
            }
            if (hasStart()) {
                builder.placedAt(startSlot, initialRoom, startParity);
            }
            return builder.build();
        }
    }

    /**
     * Initial rooms. First the rooms that are given: pinned rooms and base rooms of started units take
     * their slot, and the desired rooms of units without a start count as planned load. Then a started
     * unit without a base room takes a compatible room that is free at its start slot (the desired
     * room if it is free, else the least loaded free one, else the least loaded one). Every other unit
     * takes the compatible room with the smallest planned load (ties by the order of rooms in the
     * snapshot).
     */
    private static final class RoomWarmStart {

        private final List<UnitPlan> plans;
        private final Map<Room, Integer> load = new HashMap<>();
        // зайнятість аудиторії у слоті: [чисельник, знаменник]
        private final Map<RoomSlot, int[]> occupancy = new HashMap<>();

        RoomWarmStart(List<UnitPlan> plans) {
            this.plans = plans;
        }

        List<LessonUnit> buildUnits(Set<String> softSiblingKeys) {
            Room[] initialRooms = new Room[plans.size()];
            for (int i = 0; i < plans.size(); i++) {
                UnitPlan plan = plans.get(i);
                if (plan.isPinned()) {
                    initialRooms[i] = plan.pinnedRoom();
                    occupy(plan.pinnedRoom(), plan.pinnedSlot(), plan.pinnedParity(), plan.occurrences());
                } else if (plan.hasStart() && plan.startRoom() != null) {
                    initialRooms[i] = plan.startRoom();
                    occupy(plan.startRoom(), plan.startSlot(), plan.startParity(), plan.occurrences());
                } else if (!plan.hasStart() && plan.desiredRoom() != null) {
                    initialRooms[i] = plan.desiredRoom();
                    load.merge(plan.desiredRoom(), plan.occurrences(), Integer::sum);
                }
            }
            for (int i = 0; i < plans.size(); i++) {
                UnitPlan plan = plans.get(i);
                if (initialRooms[i] == null && plan.hasStart()) {
                    Room room = freeRoomAtStart(plan);
                    initialRooms[i] = room;
                    occupy(room, plan.startSlot(), plan.startParity(), plan.occurrences());
                }
            }
            for (int i = 0; i < plans.size(); i++) {
                if (initialRooms[i] == null) {
                    UnitPlan plan = plans.get(i);
                    Room room = leastLoaded(plan.preferredRooms());
                    load.merge(room, plan.occurrences(), Integer::sum);
                    initialRooms[i] = room;
                }
            }
            List<LessonUnit> units = new ArrayList<>(plans.size());
            for (int i = 0; i < plans.size(); i++) {
                UnitPlan plan = plans.get(i);
                units.add(plan.build(initialRooms[i], plan.siblingKey() != null
                        && softSiblingKeys.contains(plan.siblingKey())));
            }
            return units;
        }

        private Room freeRoomAtStart(UnitPlan plan) {
            Room desired = plan.desiredRoom();
            if (desired != null && isFree(desired, plan.startSlot(), plan.startParity())) {
                return desired;
            }
            for (List<Room> candidates : List.of(plan.preferredRooms(), plan.roomRange())) {
                List<Room> free = candidates.stream()
                        .filter(room -> isFree(room, plan.startSlot(), plan.startParity()))
                        .toList();
                if (!free.isEmpty()) {
                    return leastLoaded(free);
                }
            }
            return leastLoaded(plan.preferredRooms());
        }

        private Room leastLoaded(List<Room> rooms) {
            return rooms.stream()
                    .min(Comparator.comparingInt((Room candidate) -> load.getOrDefault(candidate, 0)))
                    .orElseThrow();
        }

        private boolean isFree(Room room, Timeslot slot, Parity parity) {
            int[] used = occupancy.get(new RoomSlot(room, slot));
            if (used == null) {
                return true;
            }
            for (int week : weekLanes(parity)) {
                if (used[week] >= room.getMaxParallel()) {
                    return false;
                }
            }
            return true;
        }

        private void occupy(Room room, Timeslot slot, Parity parity, int occurrences) {
            load.merge(room, occurrences, Integer::sum);
            if (room.isNone()) {
                return;
            }
            int[] used = occupancy.computeIfAbsent(new RoomSlot(room, slot), key -> new int[LessonUnit.WEEK_TYPES]);
            for (int week : weekLanes(parity)) {
                used[week]++;
            }
        }
    }

    private record RoomSlot(Room room, Timeslot slot) {
    }
}
