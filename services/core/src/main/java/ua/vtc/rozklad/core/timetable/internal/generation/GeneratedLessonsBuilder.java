package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;

/**
 * Reads the scheduler's assignments against the job's plan: WHOLE units become BOTH lessons, HALF
 * units take the returned parity (numerator or denominator). A pinned unit the solver left out
 * stays at its pinned slot; other left-out units are reported as unplaced. An assignment of an
 * unknown or duplicate unit, an invalid slot or a HALF unit without a week parity makes the whole
 * result invalid — it is not repaired silently.
 */
final class GeneratedLessonsBuilder {

    private static final int MAX_LISTED_PROBLEMS = 5;

    private GeneratedLessonsBuilder() {
    }

    static Result build(GenerationPlan plan, List<Assignment> assignments) {
        Map<String, PlanUnit> units = new LinkedHashMap<>();
        plan.units().forEach(unit -> units.put(unit.unitId(), unit));
        Set<String> placed = new HashSet<>();
        List<String> problems = new ArrayList<>();
        List<Placement> placements = new ArrayList<>();
        for (Assignment assignment : assignments) {
            PlanUnit unit = units.get(assignment.unitId());
            if (unit == null) {
                problems.add("невідоме заняття «" + assignment.unitId() + "»");
                continue;
            }
            if (!placed.add(unit.unitId())) {
                problems.add("заняття «" + unit.unitId() + "» розміщено двічі");
                continue;
            }
            if (assignment.weekday() < 1 || assignment.weekday() > 5 || assignment.pair() < 1
                    || assignment.pair() > 8) {
                problems.add("заняття «" + unit.unitId() + "» поза сіткою (день " + assignment.weekday()
                        + ", пара " + assignment.pair() + ")");
                continue;
            }
            if (unit.weight() == UnitWeight.HALF && assignment.parity() == Parity.BOTH) {
                problems.add("половинне заняття «" + unit.unitId() + "» без чисельника чи знаменника");
                continue;
            }
            Parity parity = unit.weight() == UnitWeight.WHOLE ? Parity.BOTH : assignment.parity();
            placements.add(new Placement(unit, assignment.weekday(), assignment.pair(), parity, assignment.roomId()));
        }
        if (!problems.isEmpty()) {
            throw new InvalidResultException(problems);
        }
        List<PlanUnit> unplaced = new ArrayList<>();
        for (PlanUnit unit : units.values()) {
            if (placed.contains(unit.unitId())) {
                continue;
            }
            if (unit.pinned() != null) {
                placements.add(new Placement(unit, unit.pinned().weekday(), unit.pinned().pair(),
                        unit.pinned().parity(), unit.pinned().roomId()));
            } else {
                unplaced.add(unit);
            }
        }
        return new Result(placements, unplaced);
    }

    /** Where a unit ends up; {@code roomId} may be {@code null}. */
    record Placement(PlanUnit unit, int weekday, int pair, Parity parity, UUID roomId) {
    }

    record Result(List<Placement> placements, List<PlanUnit> unplaced) {
    }

    static final class InvalidResultException extends RuntimeException {

        InvalidResultException(List<String> problems) {
            super(String.join("; ", problems.subList(0, Math.min(problems.size(), MAX_LISTED_PROBLEMS)))
                    + (problems.size() > MAX_LISTED_PROBLEMS ? " … (усього " + problems.size() + ")" : ""));
        }
    }
}
