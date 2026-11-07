package ua.vtc.rozklad.core.timetable.internal.version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ua.vtc.rozklad.core.timetable.VersionSource;
import ua.vtc.rozklad.core.timetable.VersionStatus;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;

/**
 * Element of {@code GET /api/admin/timetable/versions} (CONTRACTS.md §2) plus the semester,
 * author, generation job and notes. Conflicts and metrics are computed from the current lessons.
 */
public record TimetableVersionDto(
        UUID id,
        UUID semesterId,
        String name,
        VersionStatus status,
        VersionSource source,
        LocalDate effectiveFrom,
        Instant publishedAt,
        String publishedBy,
        UUID generationJobId,
        String notes,
        Instant createdAt,
        int lessonCount,
        int hardConflicts,
        int softWarnings,
        MetricsDto metrics) {
}
