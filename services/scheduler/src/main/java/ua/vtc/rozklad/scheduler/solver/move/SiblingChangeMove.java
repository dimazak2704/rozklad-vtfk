package ua.vtc.rozklad.scheduler.solver.move;

import ai.timefold.solver.core.api.domain.common.Lookup;
import ai.timefold.solver.core.preview.api.domain.metamodel.GenuineEntityMetaModel;
import ai.timefold.solver.core.preview.api.domain.metamodel.PlanningVariableMetaModel;
import ai.timefold.solver.core.preview.api.move.Move;
import ai.timefold.solver.core.preview.api.move.MutableSolutionView;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedCollection;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Moves the subgroup copies of one occurrence (units with one sibling key) together: each unit gets its
 * own target slot and parity in one step, so subgroups that study in parallel stay in parallel (or a
 * teacher's two subgroups stay one after the other) while they move.
 */
public final class SiblingChangeMove implements Move<Timetable> {

    private final List<LessonUnit> units;
    private final List<Timeslot> slots;
    private final List<Parity> parities;

    SiblingChangeMove(List<LessonUnit> units, List<Timeslot> slots, List<Parity> parities) {
        if (units.size() != slots.size() || units.size() != parities.size()) {
            throw new IllegalArgumentException("one slot and one parity per unit");
        }
        this.units = List.copyOf(units);
        this.slots = List.copyOf(slots);
        this.parities = List.copyOf(parities);
    }

    @Override
    public void execute(MutableSolutionView<Timetable> view) {
        GenuineEntityMetaModel<Timetable, LessonUnit> entity =
                view.getSolutionMetaModel().genuineEntity(LessonUnit.class);
        PlanningVariableMetaModel<Timetable, LessonUnit, Timeslot> timeslot =
                entity.basicVariable("timeslot", Timeslot.class);
        PlanningVariableMetaModel<Timetable, LessonUnit, Parity> parity = entity.basicVariable("parity", Parity.class);
        for (int i = 0; i < units.size(); i++) {
            view.changeVariable(timeslot, units.get(i), slots.get(i));
            view.changeVariable(parity, units.get(i), parities.get(i));
        }
    }

    @Override
    public Move<Timetable> rebase(Lookup lookup) {
        List<LessonUnit> rebased = new ArrayList<>(units.size());
        for (LessonUnit unit : units) {
            rebased.add(lookup.lookUpWorkingObject(unit));
        }
        return new SiblingChangeMove(rebased, slots, parities);
    }

    @Override
    public SequencedCollection<Object> getPlanningEntities() {
        return List.copyOf(units);
    }

    @Override
    public SequencedCollection<Object> getPlanningValues() {
        List<Object> values = new ArrayList<>(slots);
        values.addAll(parities);
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
        }
        return text.toString();
    }
}
