package ua.vtc.rozklad.scheduler.solver.score;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.LinkedHashMap;
import java.util.Map;
import ua.vtc.rozklad.contracts.events.GenerationWeights;

/**
 * Soft weights of a generation request mapped to constraints (0 switches a constraint off):
 * <ul>
 *   <li>{@code windows} → group windows; {@code teacherWindows} (falls back to {@code windows} in older
 *       requests) → teacher windows; {@code teacherShiftWait} → a teacher's wait between shifts;</li>
 *   <li>{@code brokenDays} → broken days and the bonus for paired halves; {@code latePairs} → the 4th
 *       pair of the shift;</li>
 *   <li>{@code preferences} → unwanted teacher slots; {@code homeRoom} → room preference (the teacher's
 *       usual room since round 7; the request keeps the old name for compatibility);</li>
 *   <li>{@code stability} → a unit moved from its base slot and a unit in another room than in the base;
 *       {@code specialRooms} → a lesson of a load item that kept to the sports hall or ground in the base
 *       version placed in another kind of room;</li>
 *   <li>{@code subgroupsTogether} → a lone subgroup and subgroups of one teacher not one after the other;</li>
 *   <li>{@code studentOnePairDay} → a group's one-pair day; {@code teacherDays} → a teacher's one-pair day
 *       and extra teaching days; {@code teacherOverload} → pairs above a teacher's daily limit;
 *       {@code disciplineTwice} → a discipline twice a day.</li>
 * </ul>
 * The request has no weight for a discipline repeated three times a day, so it comes from the
 * service configuration. Hard constraints are never weighted.
 */
public final class ConstraintWeights {

    private ConstraintWeights() {
    }

    public static ConstraintWeightOverrides<HardSoftScore> of(GenerationWeights weights, int disciplineRepeatWeight) {
        if (disciplineRepeatWeight < 0) {
            throw new IllegalArgumentException("disciplineRepeatWeight must not be negative");
        }
        Map<String, HardSoftScore> overrides = new LinkedHashMap<>();
        soft(overrides, TimetableConstraint.GROUP_WINDOWS, weights.windows());
        soft(overrides, TimetableConstraint.TEACHER_WINDOWS, weights.teacherWindows());
        soft(overrides, TimetableConstraint.TEACHER_SHIFT_WAIT, weights.teacherShiftWait());
        soft(overrides, TimetableConstraint.BROKEN_DAY, weights.brokenDays());
        soft(overrides, TimetableConstraint.HALF_PAIRING, weights.brokenDays());
        soft(overrides, TimetableConstraint.LATE_PAIR, weights.latePairs());
        soft(overrides, TimetableConstraint.GROUP_ONE_PAIR_DAY, weights.studentOnePairDay());
        soft(overrides, TimetableConstraint.SUBGROUP_ALONE, weights.subgroupsTogether());
        soft(overrides, TimetableConstraint.SIBLINGS_APART, weights.subgroupsTogether());
        soft(overrides, TimetableConstraint.TEACHER_ONE_PAIR_DAY, weights.teacherDays());
        soft(overrides, TimetableConstraint.TEACHER_EXTRA_DAY, weights.teacherDays());
        soft(overrides, TimetableConstraint.TEACHER_OVERLOAD, weights.teacherOverload());
        soft(overrides, TimetableConstraint.DISCIPLINE_TWICE, weights.disciplineTwice());
        soft(overrides, TimetableConstraint.DISCIPLINE_REPEAT, disciplineRepeatWeight);
        soft(overrides, TimetableConstraint.UNWANTED_SLOT, weights.preferences());
        soft(overrides, TimetableConstraint.ROOM_PREFERENCE, weights.homeRoom());
        soft(overrides, TimetableConstraint.UNIT_MOVED, weights.stability());
        soft(overrides, TimetableConstraint.ROOM_CHANGED, weights.stability());
        soft(overrides, TimetableConstraint.SPECIAL_ROOM_LOST, weights.specialRooms());
        return ConstraintWeightOverrides.of(overrides);
    }

    private static void soft(Map<String, HardSoftScore> overrides, TimetableConstraint constraint, int weight) {
        overrides.put(constraint.id(), HardSoftScore.ofSoft(weight));
    }
}
