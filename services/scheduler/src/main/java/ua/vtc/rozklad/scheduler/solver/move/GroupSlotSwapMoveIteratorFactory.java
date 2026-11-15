package ua.vtc.rozklad.scheduler.solver.move;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.random.RandomGenerator;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Local search moves that keep a group's days without windows (owner's rule of 15.11.2026, hard): everything the group
 * has at one slot swaps with everything it has at another slot, each lesson keeping its weeks; when the other slot is
 * free for the group, the lessons simply move there. Parallel subgroups, sibling copies in one slot and the numerator
 * and denominator halves of a slot move as one column, so a day that was without windows stays so when both slots are
 * taken by every cohort, and a change of teachers per slot is one step instead of two (the first of which would open a
 * window, a hard violation the late acceptance rarely climbs over). For a subgroup lesson the move swaps, every other
 * time, only that subgroup's own lessons at the two slots (a slot where the subgroup sits in a whole-group lesson gives
 * none): this puts a subgroup copy next to its sibling without touching the other cohorts. A lesson whose room is taken
 * at its target gets a free room of its range (as in {@link SiblingMoveIteratorFactory}). A slot with a pinned lesson
 * that would move, or a target outside a lesson's range (the shift window, the teacher's UNAVAILABLE slots), gives no
 * move.
 */
public class GroupSlotSwapMoveIteratorFactory implements MoveIteratorFactory<Timetable, SiblingChangeMove> {

    // спроб знайти можливий хід, перш ніж віддати порожній
    private static final int ATTEMPTS = 8;

    private Timetable builtFor;
    private List<LessonUnit> free = List.of();
    private Map<UUID, List<LessonUnit>> byGroup = Map.of();

    @Override
    public void phaseStarted(ScoreDirector<Timetable> scoreDirector) {
        refresh(scoreDirector);
    }

    @Override
    public long getSize(ScoreDirector<Timetable> scoreDirector) {
        refresh(scoreDirector);
        return free.stream().mapToLong(unit -> unit.getTimeslotRange().size() - 1L).sum();
    }

    @Override
    public Iterator<SiblingChangeMove> createOriginalMoveIterator(ScoreDirector<Timetable> scoreDirector) {
        refresh(scoreDirector);
        List<SiblingChangeMove> moves = new ArrayList<>();
        for (LessonUnit unit : free) {
            for (Timeslot target : unit.getTimeslotRange()) {
                for (boolean ownOnly : unit.getSubgroup() == 0 ? new boolean[] {false} : new boolean[] {false, true}) {
                    SiblingChangeMove move = target.equals(unit.getTimeslot()) ? null : swap(unit, target, ownOnly);
                    if (move != null) {
                        moves.add(move);
                    }
                }
            }
        }
        return moves.iterator();
    }

    @Override
    public Iterator<SiblingChangeMove> createRandomMoveIterator(ScoreDirector<Timetable> scoreDirector,
                                                                RandomGenerator random) {
        refresh(scoreDirector);
        if (free.isEmpty()) {
            return Collections.emptyIterator();
        }
        return new Iterator<>() {

            // зайнятість аудиторій на початку кроку: ітератор живе один крок, а кожен оцінений хід відкочується
            private SiblingMoveIteratorFactory.RoomUse roomUse;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public SiblingChangeMove next() {
                LessonUnit unit = null;
                for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    unit = free.get(random.nextInt(free.size()));
                    List<Timeslot> range = unit.getTimeslotRange();
                    Timeslot target = range.get(random.nextInt(range.size()));
                    boolean ownOnly = unit.getSubgroup() != 0 && random.nextBoolean();
                    SiblingChangeMove move = target.equals(unit.getTimeslot()) ? null : swap(unit, target, ownOnly);
                    if (move != null) {
                        if (roomUse == null) {
                            roomUse = SiblingMoveIteratorFactory.RoomUse.of(builtFor.getUnits());
                        }
                        return SiblingMoveIteratorFactory.withFreeRooms(move, roomUse);
                    }
                }
                // жодного можливого обміну: хід на місці нічого не змінює і не приймається як покращення
                return stay(unit);
            }
        };
    }

    /** {@link #swap(LessonUnit, Timeslot, boolean)} of whole columns. */
    SiblingChangeMove swap(LessonUnit unit, Timeslot target) {
        return swap(unit, target, false);
    }

    /**
     * Swaps the group's lessons at the unit's slot with those at {@code target} ({@code ownOnly}: only the lessons of the
     * unit's subgroup); {@code null} when a lesson that would move is pinned or its target is outside its range, or, for
     * {@code ownOnly}, when the subgroup sits in a whole-group lesson at either slot.
     */
    SiblingChangeMove swap(LessonUnit unit, Timeslot target, boolean ownOnly) {
        Timeslot origin = unit.getTimeslot();
        List<LessonUnit> units = new ArrayList<>();
        List<Timeslot> slots = new ArrayList<>();
        List<Parity> parities = new ArrayList<>();
        for (LessonUnit lesson : byGroup.getOrDefault(unit.getGroupId(), List.of())) {
            Timeslot at = lesson.getTimeslot();
            Timeslot to = origin.equals(at) ? target : target.equals(at) ? origin : null;
            if (to == null || ownOnly && lesson.getSubgroup() != unit.getSubgroup() && lesson.getSubgroup() != 0) {
                continue;
            }
            if (ownOnly && lesson.getSubgroup() == 0 || lesson.isPinned() || !lesson.getTimeslotRange().contains(to)) {
                return null;
            }
            units.add(lesson);
            slots.add(to);
            parities.add(lesson.getParity());
        }
        return units.isEmpty() ? null : new SiblingChangeMove(units, slots, parities);
    }

    private static SiblingChangeMove stay(LessonUnit unit) {
        if (unit == null) {
            throw new NoSuchElementException("no free lesson");
        }
        return new SiblingChangeMove(List.of(unit), List.of(unit.getTimeslot()), List.of(unit.getParity()));
    }

    /** Free lessons and every lesson of their groups, rebuilt when the solver works on another solution object. */
    private void refresh(ScoreDirector<Timetable> scoreDirector) {
        Timetable solution = scoreDirector.getWorkingSolution();
        if (solution == builtFor) {
            return;
        }
        Map<UUID, List<LessonUnit>> groups = new LinkedHashMap<>();
        List<LessonUnit> movable = new ArrayList<>();
        for (LessonUnit unit : solution.getUnits()) {
            if (!unit.isPinned()) {
                movable.add(unit);
            }
        }
        for (LessonUnit unit : solution.getUnits()) {
            groups.computeIfAbsent(unit.getGroupId(), id -> new ArrayList<>()).add(unit);
        }
        free = List.copyOf(movable);
        byGroup = groups;
        builtFor = solution;
    }
}
