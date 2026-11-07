package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;

/**
 * One way to handle a lesson, with the raw score used for ranking and Ukrainian reason chips.
 * {@code date}/{@code pair} are set for a make-up lesson (ADDITIONAL).
 */
public record RankedOption(
        ChangeKind kind,
        int rawScore,
        String title,
        List<String> reasons,
        List<String> warnings,
        UUID teacherId,
        UUID disciplineId,
        UUID swapLessonId,
        LocalDate date,
        Integer pair) {

    public RankedOption {
        reasons = List.copyOf(reasons);
        warnings = List.copyOf(warnings);
    }

    /** Score shown to the dispatcher, 0..100. */
    public int score() {
        return Math.clamp(rawScore, 0, 100);
    }
}
