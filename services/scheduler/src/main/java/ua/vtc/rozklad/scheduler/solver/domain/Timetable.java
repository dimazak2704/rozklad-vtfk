package ua.vtc.rozklad.scheduler.solver.domain;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.List;

/**
 * Planning solution: the weekly units of one generation job and the facts they refer to. Soft
 * constraint weights of the request are applied through {@link ConstraintWeightOverrides}.
 */
@PlanningSolution
public class Timetable {

    @ProblemFactCollectionProperty
    private List<Timeslot> timeslots;
    @ProblemFactCollectionProperty
    private List<Room> rooms;
    @ProblemFactCollectionProperty
    private List<Teacher> teachers;
    @ProblemFactCollectionProperty
    private List<StudentGroup> groups;
    @PlanningEntityCollectionProperty
    private List<LessonUnit> units;
    @PlanningScore
    private HardSoftScore score;
    private ConstraintWeightOverrides<HardSoftScore> constraintWeightOverrides;

    /** Used by the solver's solution cloner. */
    public Timetable() {
    }

    public Timetable(List<Timeslot> timeslots, List<Room> rooms, List<Teacher> teachers, List<StudentGroup> groups,
                     List<LessonUnit> units, ConstraintWeightOverrides<HardSoftScore> constraintWeightOverrides) {
        this.timeslots = List.copyOf(timeslots);
        this.rooms = List.copyOf(rooms);
        this.teachers = List.copyOf(teachers);
        this.groups = List.copyOf(groups);
        this.units = List.copyOf(units);
        this.constraintWeightOverrides = constraintWeightOverrides;
    }

    public List<Timeslot> getTimeslots() {
        return timeslots;
    }

    public List<Room> getRooms() {
        return rooms;
    }

    public List<Teacher> getTeachers() {
        return teachers;
    }

    public List<StudentGroup> getGroups() {
        return groups;
    }

    public List<LessonUnit> getUnits() {
        return units;
    }

    public HardSoftScore getScore() {
        return score;
    }

    public void setScore(HardSoftScore score) {
        this.score = score;
    }

    public ConstraintWeightOverrides<HardSoftScore> getConstraintWeightOverrides() {
        return constraintWeightOverrides;
    }

    /** Required by the solver (it accesses the overrides as a bean property). */
    public void setConstraintWeightOverrides(ConstraintWeightOverrides<HardSoftScore> constraintWeightOverrides) {
        this.constraintWeightOverrides = constraintWeightOverrides;
    }

    /** {@code true} when every unit has a timeslot, a room and a parity. */
    public boolean isFullyPlaced() {
        return units.stream().allMatch(LessonUnit::isPlaced);
    }
}
