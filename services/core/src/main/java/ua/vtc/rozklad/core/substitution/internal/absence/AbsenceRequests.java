package ua.vtc.rozklad.core.substitution.internal.absence;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;

/** Request bodies for absences; {@code pairs} empty or {@code null} = the whole day. */
public final class AbsenceRequests {

    private AbsenceRequests() {
    }

    /** {@code POST/PUT /api/admin/absences}; {@code version} turns on optimistic locking in PUT. */
    @ValidDateRange
    public record Admin(
            @NotNull UUID teacherId,
            @NotNull LocalDate dateFrom,
            @NotNull LocalDate dateTo,
            List<@NotNull @Min(1) @Max(8) Integer> pairs,
            @NotNull AbsenceReasonCategory reasonCategory,
            @Size(max = 1000) String comment,
            Long version) implements ValidDateRange.Period {

        public AbsenceData data() {
            return new AbsenceData(dateFrom, dateTo, pairs, reasonCategory, comment);
        }
    }

    /** {@code POST /api/me/absences}: the teacher is the signed-in one. */
    @ValidDateRange
    public record Mine(
            @NotNull LocalDate dateFrom,
            @NotNull LocalDate dateTo,
            List<@NotNull @Min(1) @Max(8) Integer> pairs,
            @NotNull AbsenceReasonCategory reasonCategory,
            @Size(max = 1000) String comment) implements ValidDateRange.Period {

        public AbsenceData data() {
            return new AbsenceData(dateFrom, dateTo, pairs, reasonCategory, comment);
        }
    }

    /** {@code POST /api/internal/absences} from the Telegram bot; {@code source} must be BOT (default). */
    @ValidDateRange
    public record Bot(
            @NotNull UUID teacherId,
            @NotNull LocalDate dateFrom,
            @NotNull LocalDate dateTo,
            List<@NotNull @Min(1) @Max(8) Integer> pairs,
            @NotNull AbsenceReasonCategory reasonCategory,
            @Size(max = 1000) String comment,
            AbsenceSource source) implements ValidDateRange.Period {

        public AbsenceData data() {
            return new AbsenceData(dateFrom, dateTo, pairs, reasonCategory, comment);
        }
    }

    /** Values of an absence independent of who reports it. */
    public record AbsenceData(LocalDate dateFrom, LocalDate dateTo, List<Integer> pairs,
                       AbsenceReasonCategory reasonCategory, String comment) {

        String cleanComment() {
            return comment == null || comment.isBlank() ? null : comment.strip();
        }
    }
}
