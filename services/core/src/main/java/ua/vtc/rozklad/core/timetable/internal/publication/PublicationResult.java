package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.error.ConflictDto;

/**
 * Answer of {@code POST …/versions/{id}/publish}: {@code materializedLessons} of CONTRACTS.md plus
 * what happened to lessons of applied changes.
 *
 * @param keptLessons      dated lessons from {@code effectiveFrom} on kept because a change created,
 *                         modified or refers to them
 * @param coveredByChanges template occurrences not created because a kept change governs that slot
 * @param conflicts        hard conflicts of kept lessons with the new timetable (fix them with changes)
 */
public record PublicationResult(UUID versionId, LocalDate effectiveFrom, int materializedLessons, int keptLessons,
                                int coveredByChanges, List<ConflictDto> conflicts) {

    public PublicationResult {
        conflicts = List.copyOf(conflicts);
    }
}
