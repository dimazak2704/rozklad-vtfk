package ua.vtc.rozklad.core.timetable.internal.generation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.ConstraintScore;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;

/**
 * Generation job of CONTRACTS.md §2 ({@code GET /api/admin/generation/jobs/{id}}) plus the semester,
 * base version, time limit and author. {@code bestScore} is the solver's score, e.g. {@code 0hard/-42soft}.
 */
public record GenerationJobDto(
        UUID id,
        UUID semesterId,
        GenerationStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String bestScore,
        List<ProgressPoint> progress,
        UUID resultVersionId,
        UUID baseVersionId,
        Integer timeLimitSeconds,
        List<ConstraintScore> analysis,
        MetricsDto metricsBefore,
        MetricsDto metricsAfter,
        String error,
        String requestedBy) {

    /** A point of the live chart: seconds since the start and the score at that moment. */
    public record ProgressPoint(double t, long hard, long soft) {
    }
}
