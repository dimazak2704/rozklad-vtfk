package ua.vtc.rozklad.scheduler.solver.move;

import ai.timefold.solver.core.api.domain.common.Lookup;
import ai.timefold.solver.core.preview.api.domain.metamodel.GenuineEntityMetaModel;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningVariableMetaModel;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.MutableSolutionView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SequencedCollection;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Moves several units in one step, each to its own target slot and parity: the subgroup copies of one occurrence
 * (units with one sibling key), so subgroups that study in parallel stay in parallel (or a teacher's two subgroups stay
 * one after the other) while they move, and the lessons of a group at two slots swapped as columns
 * ({@link GroupSlotSwapMoveIteratorFactory}). A unit may also get a room that is free at its target ({@code null}
 * keeps its room): together standing is a hard rule, so the copies cannot move one by one and fix their rooms
 * afterwards.
 */
public final class SiblingChangeMove implements Move<Timetable> {

    private final List<LessonUnit> units;
    private final List<Timeslot> slots;
    private final List<Parity> parities;
    private final List<Room> rooms;

    SiblingChangeMove(List<LessonUnit> units, List<Timeslot> slots, List<Parity> parities) {
        this(units, slots, parities, Collections.nCopies(units.size(), null));
    }

    /** @param rooms a room per unit, {@code null} to keep the unit's room */
    SiblingChangeMove(List<LessonUnit> units, List<Timeslot> slots, List<Parity> parities, List<Room> rooms) {
        if (units.size() != slots.size() || units.size() != parities.size() || units.size() != rooms.size()) {
            throw new IllegalArgumentException("one slot, one parity and one room (or null) per unit");
        }
        this.units = List.copyOf(units);
        this.slots = List.copyOf(slots);
        this.parities = List.copyOf(parities);
        this.rooms = Collections.unmodifiableList(new ArrayList<>(rooms));
    }

    List<LessonUnit> units() {
        return units;
    }

    List<Timeslot> slots() {
        return slots;
    }

    List<Parity> parities() {
        return parities;
    }

    /** The same move that also puts each unit into the given room ({@code null} keeps it). */
    SiblingChangeMove withRooms(List<Room> newRooms) {
        return new SiblingChangeMove(units, slots, parities, newRooms);
    }

    @Override
    public void execute(MutableSolutionView<Timetable> view) {
        GenuineEntityMetaModel<Timetable, LessonUnit> entity =
                view.getSolutionMetaModel().genuineEntity(LessonUnit.class);
        PlanningVariableMetaModel<Timetable, LessonUnit, Timeslot> timeslot =
                entity.basicVariable("timeslot", Timeslot.class);
        PlanningVariableMetaModel<Timetable, LessonUnit, Parity> parity = entity.basicVariable("parity", Parity.class);
        PlanningVariableMetaModel<Timetable, LessonUnit, Room> room = entity.basicVariable("room", Room.class);
        for (int i = 0; i < units.size(); i++) {
            view.changeVariable(timeslot, units.get(i), slots.get(i));
            view.changeVariable(parity, units.get(i), parities.get(i));
            if (rooms.get(i) != null) {
                view.changeVariable(room, units.get(i), rooms.get(i));
            }
        }
    }

    @Override
    public Move<Timetable> rebase(Lookup lookup) {
        List<LessonUnit> rebased = new ArrayList<>(units.size());
        for (LessonUnit unit : units) {
            rebased.add(lookup.lookUpWorkingObject(unit));
        }
        // аудиторії, як і слоти, — незмінні факти задачі: їх не клонують, тож перебазовувати не треба
        return new SiblingChangeMove(rebased, slots, parities, rooms);
    }

    @Override
    public SequencedCollection<Object> getPlanningEntities() {
        return List.copyOf(units);
    }

    @Override
    public SequencedCollection<Object> getPlanningValues() {
        List<Object> values = new ArrayList<>(slots);
        values.addAll(parities);
        rooms.stream().filter(java.util.Objects::nonNull).forEach(values::add);
        return values;
    }

    @Override
    public String describe() {
        return "SiblingChangeMove";
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("siblings");
        for (int i = 0; i < units.size(); i++) {
            text.append(' ').append(units.get(i).getId()).append("->").append(slots.get(i)).append('/')
                    .append(parities.get(i));
            if (rooms.get(i) != null) {
                text.append('/').append(rooms.get(i));
            }
        }
        return text.toString();
    }
}
