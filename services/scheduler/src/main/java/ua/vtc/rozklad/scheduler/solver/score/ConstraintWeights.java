package ua.vtc.rozklad.scheduler.solver.score;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.LinkedHashMap;
import java.util.Map;
import ua.vtc.rozklad.contracts.events.GenerationWeights;

/**
 * Soft weights of a generation request mapped to constraints (0 switches a constraint off):
 * {@code windows} → group and teacher windows, {@code brokenDays} → broken days and the bonus for
 * paired halves, {@code latePairs} → late pairs, {@code preferences} → unwanted teacher slots,
 * {@code homeRoom} → room preference. The request has no weight for a discipline repeated three
 * times a day, so it comes from the service configuration. Hard constraints are never weighted.
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
        soft(overrides, TimetableConstraint.TEACHER_WINDOWS, weights.windows());
        soft(overrides, TimetableConstraint.BROKEN_DAY, weights.brokenDays());
        soft(overrides, TimetableConstraint.HALF_PAIRING, weights.brokenDays());
        soft(overrides, TimetableConstraint.LATE_PAIR, weights.latePairs());
        soft(overrides, TimetableConstraint.UNWANTED_SLOT, weights.preferences());
        soft(overrides, TimetableConstraint.ROOM_PREFERENCE, weights.homeRoom());
        soft(overrides, TimetableConstraint.DISCIPLINE_REPEAT, disciplineRepeatWeight);
        return ConstraintWeightOverrides.of(overrides);
    }

    private static void soft(Map<String, HardSoftScore> overrides, TimetableConstraint constraint, int weight) {
        overrides.put(constraint.id(), HardSoftScore.ofSoft(weight));
    }
}
