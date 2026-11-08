package ua.vtc.rozklad.scheduler.solver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * hall and ground — only for units that require them or name them as preferred/home room); parity
 * BOTH for WHOLE units, NUMERATOR or DENOMINATOR for HALF units. When a filter leaves nothing, the
 * wider range is used and the matching hard constraint reports the violation.
 *
 * <p>Rooms are warm-started: every unit starts in its desired room (preferred room of the load
 * item, else the group's home room) or in the least used compatible room. A unit with an
 * {@code initial} placement (where it stands in the base version) starts there when the slot,
 * parity and room are inside its value ranges; the construction heuristic places only the units
 * that have no start, so a feasible base is improved, never rebuilt worse. Local search may still
 * change anything that is not pinned.
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
        Map<UUID, Teacher> teachers = snapshot.teachers().stream()
                .map(TimetableProblemFactory::teacher)
                .collect(Collectors.toMap(Teacher::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Integer, ShiftWindow> windows = shiftWindows(snapshot.shiftWindows());
        Map<UUID, StudentGroup> groups = snapshot.groups().stream()
                .map(group -> new StudentGroup(group.id(), group.code(), group.size(), group.shift(),
                        group.subgroups(), windows.get(group.shift())))
                .collect(Collectors.toMap(StudentGroup::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<UnitPlan> plans = snapshot.units().stream()
                .map(unit -> plan(unit, timeslots, rooms, teachers, groups))
                .toList();
        List<LessonUnit> units = new RoomWarmStart(plans).buildUnits();
        return new Timetable(timeslots, List.copyOf(rooms.values()), List.copyOf(teachers.values()),
                List.copyOf(groups.values()), units, ConstraintWeights.of(weights, disciplineRepeatWeight));
    }

    private static Teacher teacher(ProblemSnapshot.Teacher teacher) {
        return new Teacher(teacher.id(), teacher.name(), slots(teacher.unavailable()), slots(teacher.unwanted()));
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
                                 Map<UUID, Teacher> teachers, Map<UUID, StudentGroup> groups) {
        StudentGroup group = groups.get(unit.groupId());
        Teacher teacher = unit.teacherId() == null ? null : teachers.get(unit.teacherId());
        if (unit.pinned() != null) {
            ProblemSnapshot.PinnedSlot pin = unit.pinned();
            Room room = pin.roomId() == null ? Room.NONE : rooms.get(pin.roomId());
            return UnitPlan.pinned(unit, group, teacher, new Timeslot(pin.weekday(), pin.pair()), room, pin.parity());
        }
        List<Room> roomRange = roomRange(unit, group, rooms.values());
        Room preferred = unit.preferredRoomId() == null ? null : rooms.get(unit.preferredRoomId());
        Room home = unit.homeRoomId() == null ? null : rooms.get(unit.homeRoomId());
        Room desired = preferred != null && roomRange.contains(preferred) ? preferred
                : home != null && roomRange.contains(home) ? home : null;
        List<Parity> parities = unit.weight() == UnitWeight.HALF ? HALF_PARITIES : WHOLE_PARITIES;
        List<Timeslot> slotRange = timeslotRange(unit, group, teacher, timeslots);
        UnitPlan plan = UnitPlan.free(unit, group, teacher, slotRange, roomRange, parities, desired);
        ProblemSnapshot.PinnedSlot start = unit.initial();
        if (start == null) {
            return plan;
        }
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

    private static List<Room> roomRange(ProblemSnapshot.Unit unit, StudentGroup group, Collection<Room> rooms) {
        List<Room> compatible = rooms.stream()
                .filter(room -> fitsType(unit, room) && fitsCapacity(unit, group, room))
                .toList();
        if (compatible.isEmpty()) {
            log.warn("Unit {} of {}: no room of type {} fits, every room is allowed", unit.unitId(), group.getCode(),
                    unit.requiredRoomType());
            return List.copyOf(rooms);
        }
        return compatible;
    }

    private static boolean fitsType(ProblemSnapshot.Unit unit, Room room) {
        if (unit.requiredRoomType() != null) {
            return room.getType() == unit.requiredRoomType();
        }
        return !SPECIAL_PURPOSE.contains(room.getType())
                || room.getId().equals(unit.preferredRoomId()) || room.getId().equals(unit.homeRoomId());
    }

    private static boolean fitsCapacity(ProblemSnapshot.Unit unit, StudentGroup group, Room room) {
        return unit.subgroup() != 0 || !room.hasKnownCapacity() || !group.hasKnownSize()
                || room.getCapacity() >= group.getSize();
    }

    /** Everything known about a unit before its initial room is chosen. */
    private record UnitPlan(ProblemSnapshot.Unit unit, StudentGroup group, Teacher teacher,
                            List<Timeslot> timeslotRange, List<Room> roomRange, List<Parity> parityRange,
                            Room desiredRoom, Timeslot pinnedSlot, Room pinnedRoom, Parity pinnedParity,
                            Timeslot startSlot, Parity startParity, Room startRoom) {

        static UnitPlan pinned(ProblemSnapshot.Unit unit, StudentGroup group, Teacher teacher, Timeslot slot,
                               Room room, Parity parity) {
            return new UnitPlan(unit, group, teacher, List.of(), List.of(), List.of(), null, slot, room, parity,
                    null, null, null);
        }

        static UnitPlan free(ProblemSnapshot.Unit unit, StudentGroup group, Teacher teacher,
                             List<Timeslot> timeslotRange, List<Room> roomRange, List<Parity> parityRange,
                             Room desiredRoom) {
            return new UnitPlan(unit, group, teacher, timeslotRange, roomRange, parityRange, desiredRoom,
                    null, null, null, null, null, null);
        }

        /** Starts at the base placement; {@code room} {@code null} keeps the usual initial room. */
        UnitPlan startingAt(Timeslot slot, Parity parity, Room room) {
            return new UnitPlan(unit, group, teacher, timeslotRange, roomRange, parityRange, desiredRoom, null, null,
                    null, slot, parity, room);
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

        LessonUnit build(Room initialRoom) {
            LessonUnit.Builder builder = LessonUnit.builder(unit.unitId(), group, unit.disciplineId())
                    .loadItemId(unit.loadItemId())
                    .subgroup(unit.subgroup())
                    .teacher(teacher)
                    .weight(unit.weight())
                    .requiredRoomType(unit.requiredRoomType())
                    .desiredRoom(desiredRoom);
            if (isPinned()) {
                return builder.pinnedAt(pinnedSlot, pinnedRoom, pinnedParity).build();
            }
            builder.timeslotRange(timeslotRange)
                    .roomRange(roomRange)
                    .parityRange(parityRange)
                    .initialRoom(initialRoom);
            if (hasStart()) {
                builder.placedAt(startSlot, initialRoom, startParity);
            }
            return builder.build();
        }
    }

    /**
     * Initial rooms: pinned and desired rooms first, then every other unit takes the compatible
     * room with the smallest planned load (ties by the order of rooms in the snapshot).
     */
    private static final class RoomWarmStart {

        private final List<UnitPlan> plans;
        private final Map<Room, Integer> load = new HashMap<>();

        RoomWarmStart(List<UnitPlan> plans) {
            this.plans = plans;
        }

        List<LessonUnit> buildUnits() {
            Room[] initialRooms = new Room[plans.size()];
            for (int i = 0; i < plans.size(); i++) {
                UnitPlan plan = plans.get(i);
                Room fixed = plan.isPinned() ? plan.pinnedRoom()
                        : plan.startRoom() != null ? plan.startRoom() : plan.desiredRoom();
                if (fixed != null) {
                    load.merge(fixed, plan.occurrences(), Integer::sum);
                    initialRooms[i] = fixed;
                }
            }
            for (int i = 0; i < plans.size(); i++) {
                if (initialRooms[i] == null) {
                    UnitPlan plan = plans.get(i);
                    Room room = plan.roomRange().stream()
                            .min(Comparator.comparingInt((Room candidate) -> load.getOrDefault(candidate, 0)))
                            .orElseThrow();
                    load.merge(room, plan.occurrences(), Integer::sum);
                    initialRooms[i] = room;
                }
            }
            List<LessonUnit> units = new ArrayList<>(plans.size());
            for (int i = 0; i < plans.size(); i++) {
                units.add(plans.get(i).build(initialRooms[i]));
            }
            return units;
        }
    }
}
