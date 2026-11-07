package ua.vtc.rozklad.scheduler.generation.web;

import ai.timefold.solver.core.api.score.HardSoftScore;
import java.time.Instant;
import java.util.UUID;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.scheduler.generation.GenerationJob;

/**
 * A job as the scheduler sees it ({@code GET /api/scheduler/jobs/{id}}). {@code bestScore} is the
 * solver's notation (e.g. {@code 0hard/-340soft}); {@code resultDelivered} tells whether the
 * broker has confirmed {@code generation.completed}.
 */
public record GenerationJobDto(
        UUID id,
        UUID semesterId,
        GenerationStatus status,
        Instant receivedAt,
        Instant startedAt,
        Instant finishedAt,
        Integer timeLimitSeconds,
        Integer unitCount,
        String bestScore,
        Long hardScore,
        Long softScore,
        Long elapsedMs,
        boolean cancelRequested,
        String error,
        boolean resultDelivered) {

    public static GenerationJobDto of(GenerationJob job) {
        Long hard = job.getBestHardScore();
        Long soft = job.getBestSoftScore();
        String bestScore = hard == null || soft == null ? null : HardSoftScore.of(hard, soft).toString();
        return new GenerationJobDto(job.getId(), job.getSemesterId(), job.getStatus(), job.getReceivedAt(),
                job.getStartedAt(), job.getFinishedAt(), job.getTimeLimitSeconds(), job.getUnitCount(), bestScore,
                hard, soft, job.getElapsedMs(), job.isCancelRequested(), job.getError(),
                job.getCompletionSentAt() != null);
    }
}
