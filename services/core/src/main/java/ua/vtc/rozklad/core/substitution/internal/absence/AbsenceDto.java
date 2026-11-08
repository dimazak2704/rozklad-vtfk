package ua.vtc.rozklad.core.substitution.internal.absence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.substitution.AbsenceStatus;

/**
 * Absence for the dispatcher and for the teacher it belongs to. {@code reasonCategory} and
 * {@code comment} are private (DOMAIN §7): this DTO is never sent in events or SSE, see
 * {@link Summary}. {@code pairs == null} means the whole day.
 */
public record AbsenceDto(
        UUID id,
        TeacherRef teacher,
        LocalDate dateFrom,
        LocalDate dateTo,
        List<Integer> pairs,
        AbsenceReasonCategory reasonCategory,
        String comment,
        AbsenceSource source,
        AbsenceStatus status,
        String createdBy,
        Instant createdAt,
        long version) {

    /** SSE payload of {@code absence.created} / {@code absence.updated}: no reason, no comment. */
    public record Summary(UUID id, TeacherRef teacher, LocalDate dateFrom, LocalDate dateTo, List<Integer> pairs,
                          AbsenceSource source, AbsenceStatus status) {

        public static Summary of(AbsenceDto absence) {
            return new Summary(absence.id(), absence.teacher(), absence.dateFrom(), absence.dateTo(),
                    absence.pairs(), absence.source(), absence.status());
        }
    }
}
