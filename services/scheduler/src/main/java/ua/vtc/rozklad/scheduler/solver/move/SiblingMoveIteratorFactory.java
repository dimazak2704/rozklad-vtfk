package ua.vtc.rozklad.scheduler.solver.move;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Local search moves for the subgroup copies of one occurrence (M3): all free units with one sibling
 * key go to one target slot and parity together. Subgroups with different teachers go into the same
 * slot (parallel); when one teacher teaches two of them, they go to neighbouring pairs of one day
 * instead (parallel would be a teacher clash). Only slots and parities every sibling may take are
 * offered, and a group keeps only the start slots that give a valid move, so every offered move exists;
 * a group with none (one teacher whose pairs are never consecutive) offers nothing. A move (random or in
 * the original order) also gives a unit whose room is taken at its target a free room of its range (the desired room first):
 * the copies must stand together (a hard rule), so they cannot move one by one and change rooms later.
 */
public class SiblingMoveIteratorFactory implements MoveIteratorFactory<Timetable, SiblingChangeMove> {

    // спроб знайти хід, який справді щось змінює, перш ніж віддати будь-який
    private static final int ATTEMPTS = 8;

    private Timetable builtFor;
    private List<SiblingGroup> groups = List.of();

    @Override
    public void phaseStarted(ScoreDirector<Timetable> scoreDirector) {
        refresh(scoreDirector);
    }

    @Override
    public long getSize(ScoreDirector<Timetable> scoreDirector) {
        refresh(scoreDirector);
        return groups.stream().mapToLong(group -> (long) group.slots().size() * group.parities().size()).sum();
    }

    @Override
    public Iterator<SiblingChangeMove> createOriginalMoveIterator(ScoreDirector<Timetable> scoreDirector) {
        refresh(scoreDirector);
        List<SiblingChangeMove> moves = new ArrayList<>();
        for (SiblingGroup group : groups) {
            for (Timeslot slot : group.slots()) {
                for (Parity parity : group.parities()) {
                    SiblingChangeMove move = group.moveTo(slot, parity);
                    if (move != null) {
                        moves.add(move);
                    }
                }
            }
        }
        return withFreeRooms(moves.iterator(), builtFor.getUnits());
    }

    @Override
    public Iterator<SiblingChangeMove> createRandomMoveIterator(ScoreDirector<Timetable> scoreDirector,
                                                                RandomGenerator random) {
        refresh(scoreDirector);
        List<SiblingGroup> movable = groups;
        if (movable.isEmpty()) {
            return Collections.emptyIterator();
        }
        return new Iterator<>() {

            // зайнятість аудиторій на початку кроку: ітератор живе один крок, а кожен оцінений хід відкочується
            private RoomUse roomUse;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public SiblingChangeMove next() {
                // кожен слот групи дає хід (siblingGroups), тож хід є завжди; шукаємо такий, що щось змінює
                SiblingChangeMove move = null;
                for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    SiblingGroup group = movable.get(random.nextInt(movable.size()));
                    Timeslot slot = group.slots().get(random.nextInt(group.slots().size()));
                    Parity parity = group.parities().get(random.nextInt(group.parities().size()));
                    move = group.moveTo(slot, parity);
                    if (!group.isAt(move)) {
                        if (roomUse == null) {
                            roomUse = RoomUse.of(builtFor.getUnits());
                        }
                        return withFreeRooms(move, roomUse);
                    }
                }
                return move;
            }
        };
    }

    /** Sibling groups of the working solution, rebuilt when the solver works on another solution object. */
    private void refresh(ScoreDirector<Timetable> scoreDirector) {
        Timetable solution = scoreDirector.getWorkingSolution();
        if (solution != builtFor) {
            groups = siblingGroups(solution.getUnits());
            builtFor = solution;
        }
    }

    /**
     * The moves of an iterator with free rooms ({@link #withFreeRooms(SiblingChangeMove, RoomUse)}), the room use of
     * {@code all} counted at the first move: an iterator lives one step, and every move it gives is evaluated and undone
     * before the next one, as in the random iterators.
     */
    static Iterator<SiblingChangeMove> withFreeRooms(Iterator<SiblingChangeMove> moves, List<LessonUnit> all) {
        return new Iterator<>() {

            private RoomUse roomUse;

            @Override
            public boolean hasNext() {
                return moves.hasNext();
            }

            @Override
            public SiblingChangeMove next() {
                SiblingChangeMove move = moves.next();
                if (roomUse == null) {
                    roomUse = RoomUse.of(all);
                }
                return withFreeRooms(move, roomUse);
            }
        };
    }

    /** {@link #withFreeRooms(SiblingChangeMove, RoomUse)} with the room use of {@code all} counted now. */
    static SiblingChangeMove withFreeRooms(SiblingChangeMove move, List<LessonUnit> all) {
        return withFreeRooms(move, RoomUse.of(all));
    }

    /**
     * The move with a free room for every unit whose room is taken at its target slot (in a week the unit would
     * meet): its current room if free, else the desired room, else the first free room of its range; a unit
     * keeps its room when none is free (the room rule then reports it). The moving units do not count as
     * occupants, and each chosen room counts for the next unit. {@code roomUse} is read, never changed.
     */
    static SiblingChangeMove withFreeRooms(SiblingChangeMove move, RoomUse roomUse) {
        List<LessonUnit> units = move.units();
        Map<Timeslot, Map<Room, int[]>> use = roomUse.atTargetsWithout(move);
        List<Room> rooms = new ArrayList<>(units.size());
        boolean changed = false;
        for (int k = 0; k < units.size(); k++) {
            LessonUnit unit = units.get(k);
            Map<Room, int[]> atSlot = use.get(move.slots().get(k));
            int[] weeks = weekLanes(move.parities().get(k));
            Room chosen = unit.getRoom();
            if (chosen == null || !isFree(atSlot, chosen, weeks)) {
                chosen = firstFree(unit, atSlot, weeks);
            }
            occupy(atSlot, chosen, weeks);
            boolean keep = chosen.equals(unit.getRoom());
            rooms.add(keep ? null : chosen);
            changed |= !keep;
        }
        return changed ? move.withRooms(rooms) : move;
    }

    private static Room firstFree(LessonUnit unit, Map<Room, int[]> atSlot, int[] weeks) {
        Room desired = unit.getDesiredRoom();
        if (desired != null && unit.getRoomRange().contains(desired) && isFree(atSlot, desired, weeks)) {
            return desired;
        }
        for (Room candidate : unit.getRoomRange()) {
            if (isFree(atSlot, candidate, weeks)) {
                return candidate;
            }
        }
        return unit.getRoom() == null ? unit.getRoomRange().getFirst() : unit.getRoom();
    }

    private static boolean isFree(Map<Room, int[]> atSlot, Room room, int[] weeks) {
        if (room.isNone()) {
            return true;
        }
        int[] used = atSlot.get(room);
        if (used == null) {
            return true;
        }
        for (int week : weeks) {
            if (used[week] >= room.getMaxParallel()) {
                return false;
            }
        }
        return true;
    }

    private static void occupy(Map<Room, int[]> atSlot, Room room, int[] weeks) {
        if (room.isNone()) {
            return;
        }
        int[] used = atSlot.computeIfAbsent(room, ignored -> new int[LessonUnit.WEEK_TYPES]);
        for (int week : weeks) {
            used[week]++;
        }
    }

    /**
     * Lessons per room, slot and week of a solution, counted once (a random move iterator counts it at its first
     * sibling move and keeps it for the step: every move it gives is evaluated and undone before the next one), so
     * a sibling move reads two slots instead of scanning every unit.
     */
    static final class RoomUse {

        private final Map<Timeslot, Map<Room, int[]>> bySlot = new HashMap<>();

        static RoomUse of(List<LessonUnit> units) {
            RoomUse use = new RoomUse();
            for (LessonUnit unit : units) {
                if (unit.isPlaced() && !unit.getRoom().isNone()) {
                    occupy(use.bySlot.computeIfAbsent(unit.getTimeslot(), slot -> new HashMap<>()), unit.getRoom(),
                            unit.weekLanes());
                }
            }
            return use;
        }

        /** A copy of the use at the move's target slots, the moving units left out (they leave their rooms). */
        Map<Timeslot, Map<Room, int[]>> atTargetsWithout(SiblingChangeMove move) {
            Map<Timeslot, Map<Room, int[]>> use = new HashMap<>();
            for (Timeslot slot : move.slots()) {
                use.computeIfAbsent(slot, this::copyAt);
            }
            for (LessonUnit unit : move.units()) {
                Map<Room, int[]> atSlot = unit.isPlaced() && !unit.getRoom().isNone()
                        ? use.get(unit.getTimeslot()) : null;
                int[] used = atSlot == null ? null : atSlot.get(unit.getRoom());
                if (used != null) {
                    for (int week : unit.weekLanes()) {
                        used[week]--;
                    }
                }
            }
            return use;
        }

        private Map<Room, int[]> copyAt(Timeslot slot) {
            Map<Room, int[]> copy = new HashMap<>();
            bySlot.getOrDefault(slot, Map.of()).forEach((room, used) -> copy.put(room, used.clone()));
            return copy;
        }
    }

    private static int[] weekLanes(Parity parity) {
        return switch (parity) {
            case BOTH -> new int[] {LessonUnit.NUMERATOR_WEEK, LessonUnit.DENOMINATOR_WEEK};
            case NUMERATOR -> new int[] {LessonUnit.NUMERATOR_WEEK};
            case DENOMINATOR -> new int[] {LessonUnit.DENOMINATOR_WEEK};
        };
    }

    static List<SiblingGroup> siblingGroups(List<LessonUnit> units) {
        Map<String, List<LessonUnit>> byKey = new LinkedHashMap<>();
        for (LessonUnit unit : units) {
            if (unit.hasSiblingKey() && !unit.isPinned()) {
                byKey.computeIfAbsent(unit.getSiblingKey(), key -> new ArrayList<>()).add(unit);
            }
        }
        List<SiblingGroup> result = new ArrayList<>();
        for (List<LessonUnit> siblings : byKey.values()) {
            if (siblings.size() < 2) {
                continue;
            }
            List<Timeslot> slots = new ArrayList<>(siblings.getFirst().getTimeslotRange());
            List<Parity> parities = new ArrayList<>(siblings.getFirst().getParityRange());
            for (LessonUnit sibling : siblings.subList(1, siblings.size())) {
                slots.retainAll(sibling.getTimeslotRange());
                parities.retainAll(sibling.getParityRange());
            }
            if (slots.isEmpty() || parities.isEmpty()) {
                continue;
            }
            SiblingGroup candidate = new SiblingGroup(List.copyOf(siblings), List.copyOf(slots), List.copyOf(parities),
                    sharesTeacher(siblings));
            // лише стартові слоти, з яких хід існує (у спільного викладача: s, s+1, … у діапазонах усіх)
            slots.removeIf(slot -> candidate.moveTo(slot, parities.getFirst()) == null);
            if (!slots.isEmpty()) {
                result.add(new SiblingGroup(candidate.units(), List.copyOf(slots), candidate.parities(),
                        candidate.sharedTeacher()));
            }
        }
        return List.copyOf(result);
    }

    private static boolean sharesTeacher(List<LessonUnit> siblings) {
        for (int i = 0; i < siblings.size(); i++) {
            for (int j = i + 1; j < siblings.size(); j++) {
                if (siblings.get(i).hasTeacher()
                        && Objects.equals(siblings.get(i).getTeacherId(), siblings.get(j).getTeacherId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Free units of one sibling key with the slots and parities all of them may take. */
    record SiblingGroup(List<LessonUnit> units, List<Timeslot> slots, List<Parity> parities, boolean sharedTeacher) {

        /**
         * All units at {@code slot}, or, when a teacher teaches two of them, unit k at pair {@code slot + k}
         * of the same day; {@code null} when such a slot is outside a unit's range.
         */
        SiblingChangeMove moveTo(Timeslot slot, Parity parity) {
            List<Timeslot> targets = new ArrayList<>(units.size());
            for (int k = 0; k < units.size(); k++) {
                Timeslot target = sharedTeacher ? inRange(units.get(k), slot.weekday(), slot.pair() + k) : slot;
                if (target == null) {
                    return null;
                }
                targets.add(target);
            }
            return new SiblingChangeMove(units, targets, Collections.nCopies(units.size(), parity));
        }

        /** Every unit already stands where the move would put it. */
        boolean isAt(SiblingChangeMove move) {
            List<Object> values = new ArrayList<>(move.getPlanningValues());
            for (int k = 0; k < units.size(); k++) {
                LessonUnit unit = units.get(k);
                if (!Objects.equals(unit.getTimeslot(), values.get(k))
                        || unit.getParity() != values.get(units.size() + k)) {
                    return false;
                }
            }
            return true;
        }

        private static Timeslot inRange(LessonUnit unit, int weekday, int pair) {
            for (Timeslot candidate : unit.getTimeslotRange()) {
                if (candidate.weekday() == weekday && candidate.pair() == pair) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
