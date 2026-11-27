package ua.vtc.rozklad.core.timetable.internal.generation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.GenerationStopReason;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.ConstraintScore;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;

/**
 * Generation job of CONTRACTS.md §2 ({@code GET /api/admin/generation/jobs/{id}}) plus the semester,
 * base version, time limit and author. {@code bestScore} is the solver's score, e.g. {@code 0hard/-42soft}.
 * Additive (genfix B8): what the job was asked ({@code scope} as requested, {@code groups} as resolved,
 * normalised {@code weights}), the score parsed into {@code hardScore} / {@code softScore} ({@code null}
 * before the first score) and the {@code outcome} against the base version (SOLVED jobs only, else
 * {@code null}). {@code legacyWeights} (genfix repair): {@code true} when the job stored only the five weights of
 * the first schema, so the other rules did not exist for that run and {@code weights} shows their defaults only
 * to fill the form; {@code false} for newer jobs, {@code null} when the job stored no weights. Additive (auto mode):
 * {@code timeLimitSeconds} is {@code null} for a job without a limit; {@code stopRequested} — the dispatcher stopped
 * the running job and its best timetable is on the way; {@code stopReason} — why a job with a timetable stopped
 * ({@code null} for older jobs and jobs without a timetable). Additive (base score): {@code baseHardScore} /
 * {@code baseSoftScore} — the solver's score of the timetable the run started from, under the run's own rules and
 * weights, before solving; {@code null} for a run from scratch, for older jobs and from older schedulers.
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
        String requestedBy,
        Scope scope,
        List<GroupRef> groups,
        GenerationWeights weights,
        Long hardScore,
        Long softScore,
        GenerationOutcome outcome,
        Boolean legacyWeights,
        boolean stopRequested,
        GenerationStopReason stopReason,
        Long baseHardScore,
        Long baseSoftScore) {

    /** A point of the live chart: seconds since the start and the score at that moment. */
    public record ProgressPoint(double t, long hard, long soft) {
    }

    /** The scope as requested; both lists empty = every active group. */
    public record Scope(List<UUID> groupIds, List<Integer> courses) {

        public Scope {
            groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
            courses = courses == null ? List.of() : List.copyOf(courses);
        }
    }
}
