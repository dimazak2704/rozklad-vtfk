package ua.vtc.rozklad.core.substitution.internal.options;

import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;

/**
 * One way to handle a lesson after ranking: its band and integer cost (lower is better), Ukrainian
 * reason and warning chips, and the ids a change needs. {@code swapLessonId} is the moved lesson of a
 * SWAP or the other subgroup's lesson of a MERGE_SUBGROUPS; {@code roomId} the suggested room (the
 * substitute's own room, the merged class's room), {@code null} for «the lesson's room».
 * {@code score} only reflects the position (100, 90, …).
 */
public record RankedOption(
        ChangeKind kind,
        Band band,
        int cost,
        String title,
        List<String> reasons,
        List<String> warnings,
        UUID teacherId,
        UUID disciplineId,
        UUID swapLessonId,
        UUID roomId,
        boolean recommended,
        int score) {

    public RankedOption {
        reasons = List.copyOf(reasons);
        warnings = List.copyOf(warnings);
    }
}
