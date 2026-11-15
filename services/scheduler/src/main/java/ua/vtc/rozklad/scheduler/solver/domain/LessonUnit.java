package ua.vtc.rozklad.scheduler.solver.domain;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LatePairs;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;

/**
 * One weekly lesson to place (planning entity): the solver chooses its timeslot, room and parity.
 * Value ranges are per unit: the group's shift window without the teacher's UNAVAILABLE slots,
 * compatible rooms, and {@code BOTH} for a WHOLE unit or {@code NUMERATOR}/{@code DENOMINATOR}
 * for a HALF unit. A pinned unit keeps the dispatcher's placement. A unit may know its <em>base
 * placement</em> (where it stands in the base version, for the stability rules) and its
 * {@code siblingKey} (equal on the subgroup copies of one weekly occurrence of a split load item), and the
 * special-purpose room types its load item kept to in the base version ({@link #getBaseRoomTypes()}).
 *
 * <p>Week arithmetic: a lesson with parity BOTH meets in both week types (numerator and
 * denominator weeks), NUMERATOR/DENOMINATOR in one. Penalties count such week occurrences.
 */
@PlanningEntity(comparatorFactoryClass = LessonUnitDifficulty.class)
public class LessonUnit {

    /** Lane of the numerator week in {@link #weekLanes()}; the denominator week is lane 1. */
    public static final int NUMERATOR_WEEK = 0;
    public static final int DENOMINATOR_WEEK = 1;
    public static final int WEEK_TYPES = 2;

    private static final int[][] WEEK_LANES = {
            {NUMERATOR_WEEK, DENOMINATOR_WEEK}, // BOTH
            {NUMERATOR_WEEK},                   // NUMERATOR
            {DENOMINATOR_WEEK}};                // DENOMINATOR

    @PlanningId
    private String id;
    private UUID loadItemId;
    private StudentGroup group;
    private int subgroup;
    private UUID disciplineId;
    private Teacher teacher;
    private UnitWeight weight;
    private RoomType requiredRoomType;
    private Room desiredRoom;
    private UUID keptRoomId;
    private String siblingKey;
    // місце в базовій версії (null — заняття в базі немає)
    private Timeslot baseSlot;
    private Parity baseParity;
    private UUID baseRoomId;
    // типи спецаудиторій (спортзал, майданчик), де стояли всі базові пари предмета; порожньо — правила немає
    private Set<RoomType> baseRoomTypes;
    // доріжки «когорта × тип тижня» для кожної парності (індекс — Parity.ordinal())
    private int[][] cohortLanesByParity;

    @PlanningPin
    private boolean pinned;

    @ValueRangeProvider(id = "timeslotRange")
    private List<Timeslot> timeslotRange;
    @ValueRangeProvider(id = "roomRange")
    private List<Room> roomRange;
    @ValueRangeProvider(id = "parityRange")
    private List<Parity> parityRange;

    @PlanningVariable(valueRangeProviderRefs = "timeslotRange")
    private Timeslot timeslot;
    @PlanningVariable(valueRangeProviderRefs = "roomRange")
    private Room room;
    @PlanningVariable(valueRangeProviderRefs = "parityRange")
    private Parity parity;

    /** Used by the solver's solution cloner. */
    public LessonUnit() {
    }

    private LessonUnit(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.loadItemId = builder.loadItemId;
        this.group = Objects.requireNonNull(builder.group, "group");
        this.subgroup = builder.subgroup;
        this.disciplineId = Objects.requireNonNull(builder.disciplineId, "disciplineId");
        this.teacher = builder.teacher;
        this.weight = Objects.requireNonNull(builder.weight, "weight");
        this.requiredRoomType = builder.requiredRoomType;
        this.desiredRoom = builder.desiredRoom;
        this.keptRoomId = builder.keptRoomId;
        this.siblingKey = builder.siblingKey;
        this.baseSlot = builder.baseSlot;
        this.baseParity = builder.baseParity;
        this.baseRoomId = builder.baseRoomId;
        this.baseRoomTypes = builder.baseRoomTypes.isEmpty() ? Set.of() : Set.copyOf(builder.baseRoomTypes);
        this.timeslotRange = List.copyOf(builder.timeslotRange);
        this.roomRange = List.copyOf(builder.roomRange);
        this.parityRange = List.copyOf(builder.parityRange);
        if (timeslotRange.isEmpty() || roomRange.isEmpty() || parityRange.isEmpty()) {
            throw new IllegalArgumentException("unit " + id + " has an empty value range");
        }
        this.cohortLanesByParity = cohortLanes(group.cohortIndexesOf(subgroup));
        this.timeslot = builder.timeslot;
        this.room = builder.room;
        this.parity = builder.parity;
        this.pinned = builder.pinned;
    }

    public static Builder builder(String id, StudentGroup group, UUID disciplineId) {
        return new Builder(id, group, disciplineId);
    }

    private static int[][] cohortLanes(int[] cohortIndexes) {
        int[][] lanes = new int[Parity.values().length][];
        for (Parity value : Parity.values()) {
            int[] weeks = WEEK_LANES[value.ordinal()];
            int[] result = new int[cohortIndexes.length * weeks.length];
            int i = 0;
            for (int cohort : cohortIndexes) {
                for (int week : weeks) {
                    result[i++] = cohort * WEEK_TYPES + week;
                }
            }
            lanes[value.ordinal()] = result;
        }
        return lanes;
    }

    // ---- planning variables

    public Timeslot getTimeslot() {
        return timeslot;
    }

    public void setTimeslot(Timeslot timeslot) {
        this.timeslot = timeslot;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public Parity getParity() {
        return parity;
    }

    public void setParity(Parity parity) {
        this.parity = parity;
    }

    // ---- facts of the unit

    public String getId() {
        return id;
    }

    public UUID getLoadItemId() {
        return loadItemId;
    }

    public StudentGroup getGroup() {
        return group;
    }

    public UUID getGroupId() {
        return group.getId();
    }

    public int getSubgroup() {
        return subgroup;
    }

    public UUID getDisciplineId() {
        return disciplineId;
    }

    public Teacher getTeacher() {
        return teacher;
    }

    public UUID getTeacherId() {
        return teacher == null ? null : teacher.getId();
    }

    public boolean hasTeacher() {
        return teacher != null;
    }

    public UnitWeight getWeight() {
        return weight;
    }

    public RoomType getRequiredRoomType() {
        return requiredRoomType;
    }

    public Room getDesiredRoom() {
        return desiredRoom;
    }

    public boolean isPinned() {
        return pinned;
    }

    /**
     * The room an online pinned lesson keeps for reference (DOMAIN §5): it stands in
     * {@link Room#NONE} for the solver and gets this room back in the result; otherwise {@code null}.
     */
    public UUID getKeptRoomId() {
        return keptRoomId;
    }

    /** Key shared by the subgroup copies of one weekly occurrence of a split load item, or {@code null}. */
    public String getSiblingKey() {
        return siblingKey;
    }

    public boolean hasSiblingKey() {
        return siblingKey != null;
    }

    /** Has a sibling key and a teacher (the input of «Підгрупи одного викладача не підряд»). */
    public boolean hasSiblingWithTeacher() {
        return siblingKey != null && teacher != null;
    }

    /** Slot of the unit in the base version, {@code null} when it has none. */
    public Timeslot getBaseSlot() {
        return baseSlot;
    }

    public Parity getBaseParity() {
        return baseParity;
    }

    /** Room of the unit in the base version, {@code null} when it has none (or no base placement). */
    public UUID getBaseRoomId() {
        return baseRoomId;
    }

    public boolean hasBase() {
        return baseSlot != null;
    }

    /**
     * Special-purpose room types (sports hall, sports ground) that every base lesson of the unit's load item
     * with a room stood in; empty when the item has no such base (no base, or a lesson in an ordinary room).
     */
    public Set<RoomType> getBaseRoomTypes() {
        return baseRoomTypes;
    }

    public List<Timeslot> getTimeslotRange() {
        return timeslotRange;
    }

    public List<Room> getRoomRange() {
        return roomRange;
    }

    public List<Parity> getParityRange() {
        return parityRange;
    }

    public boolean isPlaced() {
        return timeslot != null && room != null && parity != null;
    }

    // ---- derived values used by the constraints

    public int getWeekday() {
        return timeslot.weekday();
    }

    public int getPair() {
        return timeslot.pair();
    }

    /** Number of week types the lesson meets in: 2 for BOTH, 1 for NUMERATOR or DENOMINATOR. */
    public int weeklyOccurrences() {
        return parity == Parity.BOTH ? 2 : 1;
    }

    public boolean isHalf() {
        return parity != Parity.BOTH;
    }

    public long numeratorOccupancy() {
        return parity == Parity.DENOMINATOR ? 0 : 1;
    }

    public long denominatorOccupancy() {
        return parity == Parity.NUMERATOR ? 0 : 1;
    }

    /** Week types in which both lessons meet: 2 (both BOTH), 1 or 0 (numerator vs denominator). */
    public int weeksSharedWith(LessonUnit other) {
        if (parity == Parity.BOTH && other.parity == Parity.BOTH) {
            return 2;
        }
        return parity.overlaps(other.parity) ? 1 : 0;
    }

    /** Subgroup 0 (whole group) overlaps every subgroup; subgroup k overlaps 0 and k. */
    public boolean cohortsOverlap(LessonUnit other) {
        return subgroup == 0 || other.subgroup == 0 || subgroup == other.subgroup;
    }

    /** Lanes «cohort × week type» the lesson occupies (see {@link StudentGroup#cohortIndexesOf}). */
    public int[] cohortLanes() {
        return cohortLanesByParity[parity.ordinal()];
    }

    /** Week-type lanes the lesson occupies: {@link #NUMERATOR_WEEK} and/or {@link #DENOMINATOR_WEEK}. */
    public int[] weekLanes() {
        return WEEK_LANES[parity.ordinal()];
    }

    public boolean violatesRoomType() {
        return requiredRoomType != null && !room.isNone() && room.getType() != requiredRoomType;
    }

    public boolean exceedsRoomCapacity() {
        return subgroup == 0 && room.hasKnownCapacity() && group.hasKnownSize()
                && room.getCapacity() < group.getSize();
    }

    public boolean isOutsideShift() {
        return !group.allows(timeslot);
    }

    public boolean isTeacherUnavailable() {
        return teacher != null && teacher.isUnavailableAt(timeslot);
    }

    public boolean isTeacherUnwanted() {
        return teacher != null && teacher.isUnwantedAt(timeslot);
    }

    public boolean isOutsideDesiredRoom() {
        return desiredRoom != null && !room.isNone() && !room.equals(desiredRoom);
    }

    /** 0-based position of the pair inside the group's shift on that weekday. */
    public int positionInShift() {
        return group.getWindow().positionOf(timeslot);
    }

    /** «4-та пара зміни» or later ({@link LatePairs}, the one definition shared with core and the web). */
    public boolean isLate() {
        return LatePairs.isLate(timeslot.pair(), group.getWindow().firstPairOn(timeslot.weekday()));
    }

    /** The 4th pair of the shift costs 1, the 5th 2, …, per week occurrence; 0 when not late. */
    public int latePenalty() {
        return isLate() ? (positionInShift() - LatePairs.FIRST_LATE_POSITION + 1) * weeklyOccurrences() : 0;
    }

    /** Shift of the group the lesson belongs to. */
    public int getShift() {
        return group.getShift();
    }

    /** The unit stands in another slot or week than in the base version. */
    public boolean isMovedFromBase() {
        return baseSlot != null && (!baseSlot.equals(timeslot) || baseParity != parity);
    }

    /** The unit kept its base slot and week but sits in another room than in the base version. */
    public boolean isRoomChangedFromBase() {
        return baseRoomId != null && !isMovedFromBase() && !room.isNone() && !baseRoomId.equals(room.getId());
    }

    /** The load item kept to special-purpose rooms in the base version, and this lesson sits in another kind of room. */
    public boolean isOutsideBaseRoomTypes() {
        return !baseRoomTypes.isEmpty() && !room.isNone() && !baseRoomTypes.contains(room.getType());
    }

    /** Same weekday, neighbouring pairs. */
    public boolean isAdjacentTo(LessonUnit other) {
        return timeslot.weekday() == other.timeslot.weekday() && Math.abs(timeslot.pair() - other.timeslot.pair()) == 1;
    }

    @Override
    public String toString() {
        return id + "@" + timeslot + "/" + parity + "/" + room;
    }

    /** Creates units with their value ranges; the solver fills the variables unless pinned. */
    public static final class Builder {

        private final String id;
        private final StudentGroup group;
        private final UUID disciplineId;
        private UUID loadItemId;
        private int subgroup;
        private Teacher teacher;
        private UnitWeight weight = UnitWeight.WHOLE;
        private RoomType requiredRoomType;
        private Room desiredRoom;
        private UUID keptRoomId;
        private String siblingKey;
        private Timeslot baseSlot;
        private Parity baseParity;
        private UUID baseRoomId;
        private Set<RoomType> baseRoomTypes = Set.of();
        private List<Timeslot> timeslotRange = List.of();
        private List<Room> roomRange = List.of();
        private List<Parity> parityRange = List.of();
        private Timeslot timeslot;
        private Room room;
        private Parity parity;
        private boolean pinned;

        private Builder(String id, StudentGroup group, UUID disciplineId) {
            this.id = id;
            this.group = group;
            this.disciplineId = disciplineId;
        }

        public Builder loadItemId(UUID loadItemId) {
            this.loadItemId = loadItemId;
            return this;
        }

        public Builder subgroup(int subgroup) {
            this.subgroup = subgroup;
            return this;
        }

        public Builder teacher(Teacher teacher) {
            this.teacher = teacher;
            return this;
        }

        public Builder weight(UnitWeight weight) {
            this.weight = weight;
            return this;
        }

        public Builder requiredRoomType(RoomType requiredRoomType) {
            this.requiredRoomType = requiredRoomType;
            return this;
        }

        public Builder desiredRoom(Room desiredRoom) {
            this.desiredRoom = desiredRoom;
            return this;
        }

        /** Room an online pinned lesson keeps for reference; it occupies {@link Room#NONE}. */
        public Builder keptRoomId(UUID keptRoomId) {
            this.keptRoomId = keptRoomId;
            return this;
        }

        public Builder siblingKey(String siblingKey) {
            this.siblingKey = siblingKey;
            return this;
        }

        /** Where the unit stands in the base version ({@code roomId} may be {@code null}). */
        public Builder base(Timeslot slot, Parity parity, UUID roomId) {
            this.baseSlot = Objects.requireNonNull(slot, "slot");
            this.baseParity = Objects.requireNonNull(parity, "parity");
            this.baseRoomId = roomId;
            return this;
        }

        /** Special-purpose room types the load item kept to in the base version (soft rule «specialRooms»). */
        public Builder baseRoomTypes(Set<RoomType> types) {
            this.baseRoomTypes = types == null || types.isEmpty() ? Set.of() : EnumSet.copyOf(types);
            return this;
        }

        public Builder timeslotRange(List<Timeslot> timeslotRange) {
            this.timeslotRange = timeslotRange;
            return this;
        }

        public Builder roomRange(List<Room> roomRange) {
            this.roomRange = roomRange;
            return this;
        }

        public Builder parityRange(List<Parity> parityRange) {
            this.parityRange = parityRange;
            return this;
        }

        /** Initial room before solving (a warm start the solver may change). */
        public Builder initialRoom(Room room) {
            this.room = room;
            return this;
        }

        /** Fixes the unit: the solver never moves it. */
        public Builder pinnedAt(Timeslot timeslot, Room room, Parity parity) {
            this.timeslot = Objects.requireNonNull(timeslot, "timeslot");
            this.room = Objects.requireNonNull(room, "room");
            this.parity = Objects.requireNonNull(parity, "parity");
            this.timeslotRange = List.of(timeslot);
            this.roomRange = List.of(room);
            this.parityRange = List.of(parity);
            this.pinned = true;
            return this;
        }

        /** Places the unit without pinning it (tests and constraint verification). */
        public Builder placedAt(Timeslot timeslot, Room room, Parity parity) {
            this.timeslot = timeslot;
            this.room = room;
            this.parity = parity;
            return this;
        }

        public LessonUnit build() {
            if (timeslotRange.isEmpty() && timeslot != null) {
                timeslotRange = List.of(timeslot);
            }
            if (roomRange.isEmpty() && room != null) {
                roomRange = List.of(room);
            }
            if (parityRange.isEmpty() && parity != null) {
                parityRange = List.of(parity);
            }
            return new LessonUnit(this);
        }
    }
}
