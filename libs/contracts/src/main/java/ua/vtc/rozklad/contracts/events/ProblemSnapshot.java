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

    /** {@code subgroups} lists stored subgroup numbers (1..n); 0 (whole group) is implicit. */
    public record Group(UUID id, String code, int size, int shift, List<Integer> subgroups) {

        public Group {
            Objects.requireNonNull(id, "id");
            subgroups = EventLists.copy(subgroups);
        }
    }

    public record Room(UUID id, String name, RoomType type, int capacity, int maxParallel) {

        public Room {
            Objects.requireNonNull(id, "id");
        }
    }

    public record Teacher(UUID id, String name, List<Timeslot> unavailable, List<Timeslot> unwanted) {

        public Teacher {
            Objects.requireNonNull(id, "id");
            unavailable = EventLists.copy(unavailable);
            unwanted = EventLists.copy(unwanted);
        }
    }

    /**
     * One weekly lesson to place. {@code unitId} is opaque and unique within the snapshot;
     * {@code subgroup} 0 means the whole group. Nullable: teacherId, requiredRoomType,
     * preferredRoomId, homeRoomId, pinned, initial. {@code initial} (additive) is where the unit
     * stands in the base version: a warm start the solver may change, so a feasible base is never
     * returned worse; {@code pinned} fixes the unit.
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
            PinnedSlot initial) {

        public Unit {
            Objects.requireNonNull(unitId, "unitId");
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(disciplineId, "disciplineId");
            Objects.requireNonNull(weight, "weight");
        }

        /** A unit without a warm start. */
        public Unit(String unitId, UUID loadItemId, UUID groupId, int subgroup, UUID disciplineId, UUID teacherId,
                    UnitWeight weight, RoomType requiredRoomType, UUID preferredRoomId, UUID homeRoomId,
                    PinnedSlot pinned) {
            this(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, requiredRoomType,
                    preferredRoomId, homeRoomId, pinned, null);
        }
    }

    /** A unit fixed by the dispatcher; {@code roomId} may be {@code null}. */
    public record PinnedSlot(int weekday, int pair, Parity parity, UUID roomId) {
    }
}
