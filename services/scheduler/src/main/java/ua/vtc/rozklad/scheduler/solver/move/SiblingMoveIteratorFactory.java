package ua.vtc.rozklad.scheduler.solver.move;

import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveIteratorFactory;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Local search moves for the subgroup copies of one occurrence (M3): all free units with one sibling
 * key go to one target slot and parity together. Subgroups with different teachers go into the same
 * slot (parallel); when one teacher teaches two of them, they go to neighbouring pairs of one day
 * instead (parallel would be a teacher clash). Only slots and parities every sibling may take are
 * offered, and a group keeps only the start slots that give a valid move, so every offered move exists;
 * a group with none (one teacher whose pairs are never consecutive) offers nothing.
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
        return moves.iterator();
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
                        return move;
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
