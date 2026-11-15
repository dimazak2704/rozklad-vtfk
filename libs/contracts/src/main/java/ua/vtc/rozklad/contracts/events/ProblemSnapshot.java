package ua.vtc.rozklad.contracts.events;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;

/**
 * Everything the scheduler needs to build a timetable; the scheduler has no access to the core DB.
 * Weekdays are 1 (Monday) to 5 (Friday), pairs start at 1.
 */
public record ProblemSnapshot(
        List<Timeslot> timeslots,
        List<ShiftWindow> shiftWindows,
        List<Group> groups,
        List<Room> rooms,
        List<Teacher> teachers,
        List<Unit> units) {

    public ProblemSnapshot {
        timeslots = EventLists.copy(timeslots);
        shiftWindows = EventLists.copy(shiftWindows);
        groups = EventLists.copy(groups);
        rooms = EventLists.copy(rooms);
        teachers = EventLists.copy(teachers);
        units = EventLists.copy(units);
    }

    public record Timeslot(int weekday, int pair) {
    }

    public record ShiftWindow(int shift, int weekday, int firstPair, int lastPair) {
    }

    /**
     * {@code subgroups} lists stored subgroup numbers (1..n); 0 (whole group) is implicit. {@code fridayMaxPairs}
     * (additive, nullable) is the most pairs the group may have on Friday, a hard rule of the scheduler for the
     * group-days it may change (owner's rule of 15.11.2026: Friday is an unloading day); {@code null} means no limit
     * (snapshots before it). Core sends the college setting {@code generation.fridayMaxPairs} for every group.
     */
    public record Group(UUID id, String code, int size, int shift, List<Integer> subgroups, Integer fridayMaxPairs) {

        public Group {
            Objects.requireNonNull(id, "id");
            subgroups = EventLists.copy(subgroups);
            if (fridayMaxPairs != null && fridayMaxPairs < 1) {
                throw new IllegalArgumentException("fridayMaxPairs must be positive");
            }
        }

        /** A group without a Friday limit (snapshots before the limit). */
        public Group(UUID id, String code, int size, int shift, List<Integer> subgroups) {
            this(id, code, size, shift, subgroups, null);
        }
    }

    public record Room(UUID id, String name, RoomType type, int capacity, int maxParallel) {

        public Room {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * {@code maxPairsPerDay} (additive, nullable) is the teacher's daily pair limit for the soft rule
     * «Забагато пар на день у викладача»; {@code null} means no limit (snapshots before it). Core sends the
     * college setting {@code generation.teacherMaxPairsPerDay} for every teacher.
     */
    public record Teacher(UUID id, String name, List<Timeslot> unavailable, List<Timeslot> unwanted,
                          Integer maxPairsPerDay) {

        public Teacher {
            Objects.requireNonNull(id, "id");
            unavailable = EventLists.copy(unavailable);
            unwanted = EventLists.copy(unwanted);
            if (maxPairsPerDay != null && maxPairsPerDay < 1) {
                throw new IllegalArgumentException("maxPairsPerDay must be positive");
            }
        }

        /** A teacher without a daily limit (snapshots before the limit). */
        public Teacher(UUID id, String name, List<Timeslot> unavailable, List<Timeslot> unwanted) {
            this(id, name, unavailable, unwanted, null);
        }
    }

    /**
     * One weekly lesson to place. {@code unitId} is opaque and unique within the snapshot;
     * {@code subgroup} 0 means the whole group. Nullable: teacherId, requiredRoomType,
     * preferredRoomId, homeRoomId, pinned, initial, teacherRoomId. {@code initial} (additive) is where
     * the unit stands in the base version: a warm start the solver may change, so a feasible base is
     * never returned worse; {@code pinned} fixes the unit. {@code teacherRoomId} (additive, round 7)
     * is the teacher's usual room for the discipline (else for any discipline) in the published
     * timetable: the room belongs to the teacher and groups come to it. The solver prefers
     * {@code preferredRoomId} (load item), then {@code teacherRoomId}, then {@code homeRoomId}; core
     * sends {@code homeRoomId} as {@code null} since round 7 (kept for older snapshots).
     * {@code online} (additive; absent or null reads as {@code false}) marks a pinned unit that stands for an online
     * template lesson (DOMAIN §5): its {@code pinned.roomId} is kept for reference only, so the
     * scheduler lets it occupy no room, checks no capacity or room type for it and returns the room
     * unchanged in the assignment. Free units are always placed in a room, whatever the flag says.
     *
     * <p>{@code initial} is also the base placement: the scheduler measures stability against it, and the
     * {@code teacherId} of a unit matched to a base lesson is that lesson's teacher. {@code siblingKey}
     * (additive, nullable) is equal on the subgroup copies of one weekly occurrence of a split load item
     * ({@code loadItemId:w1}, {@code :w2}, {@code :h}); core sets it on free units of an item with two or
     * more subgroups, otherwise {@code null}.
     */
    public record Unit(
            String unitId,
            UUID loadItemId,
            UUID groupId,
            int subgroup,
            UUID disciplineId,
            UUID teacherId,
            UnitWeight weight,
            RoomType requiredRoomType,
            UUID preferredRoomId,
            UUID homeRoomId,
            PinnedSlot pinned,
            PinnedSlot initial,
            UUID teacherRoomId,
            Boolean online,
            String siblingKey) {

        public Unit {
            Objects.requireNonNull(unitId, "unitId");
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(disciplineId, "disciplineId");
            Objects.requireNonNull(weight, "weight");
            // Jackson 3 refuses an absent primitive: the wrapper reads older snapshots, null means in person
            online = Boolean.TRUE.equals(online);
        }

        /** A unit without a sibling key (snapshots before subgroup siblings). */
        public Unit(String unitId, UUID loadItemId, UUID groupId, int subgroup, UUID disciplineId, UUID teacherId,
                    UnitWeight weight, RoomType requiredRoomType, UUID preferredRoomId, UUID homeRoomId,
                    PinnedSlot pinned, PinnedSlot initial, UUID teacherRoomId, Boolean online) {
            this(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, requiredRoomType,
                    preferredRoomId, homeRoomId, pinned, initial, teacherRoomId, online, null);
        }

        /** An in-person unit (snapshots before the online flag). */
        public Unit(String unitId, UUID loadItemId, UUID groupId, int subgroup, UUID disciplineId, UUID teacherId,
                    UnitWeight weight, RoomType requiredRoomType, UUID preferredRoomId, UUID homeRoomId,
                    PinnedSlot pinned, PinnedSlot initial, UUID teacherRoomId) {
            this(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, requiredRoomType,
                    preferredRoomId, homeRoomId, pinned, initial, teacherRoomId, false);
        }

        /** A unit without a teacher room (snapshots before round 7). */
        public Unit(String unitId, UUID loadItemId, UUID groupId, int subgroup, UUID disciplineId, UUID teacherId,
                    UnitWeight weight, RoomType requiredRoomType, UUID preferredRoomId, UUID homeRoomId,
                    PinnedSlot pinned, PinnedSlot initial) {
            this(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, requiredRoomType,
                    preferredRoomId, homeRoomId, pinned, initial, null, false);
        }

        /** A unit without a warm start. */
        public Unit(String unitId, UUID loadItemId, UUID groupId, int subgroup, UUID disciplineId, UUID teacherId,
                    UnitWeight weight, RoomType requiredRoomType, UUID preferredRoomId, UUID homeRoomId,
                    PinnedSlot pinned) {
            this(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, requiredRoomType,
                    preferredRoomId, homeRoomId, pinned, null, null, false);
        }
    }

    /** A unit fixed by the dispatcher; {@code roomId} may be {@code null}. */
    public record PinnedSlot(int weekday, int pair, Parity parity, UUID roomId) {
    }
}
