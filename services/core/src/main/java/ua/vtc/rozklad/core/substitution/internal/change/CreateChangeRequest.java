package ua.vtc.rozklad.core.substitution.internal.change;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.substitution.ReasonCode;

/**
 * {@code POST /api/admin/changes}. Which of the optional ids are required depends on the kind
 * ({@link ChangeKindRules}); {@code pairTo} defaults to {@code pairFrom}, {@code subgroup} to the
 * original lesson's cohort (0 = whole group).
 */
@ValidChangeRequest
public record CreateChangeRequest(
        @NotNull ChangeKind kind,
        @NotNull LocalDate date,
        @NotNull @Min(1) @Max(8) Integer pairFrom,
        @Min(1) @Max(8) Integer pairTo,
        @NotNull UUID groupId,
        @Min(0) @Max(8) Integer subgroup,
        UUID originalLessonId,
        UUID newDisciplineId,
        UUID newTeacherId,
        UUID newRoomId,
        UUID swapLessonId,
        @NotNull ReasonCode reasonCode,
        @Size(max = 100) String reasonText,
        UUID absenceId) {

    public int lastPair() {
        return pairTo != null ? pairTo : pairFrom;
    }
}
