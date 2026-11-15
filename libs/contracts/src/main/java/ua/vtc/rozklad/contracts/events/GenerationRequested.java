package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code generation.requested}: core → scheduler. {@code weights == null} means defaults. {@code timeLimitSeconds}
 * (positive) is the old fixed limit; {@code null} (additive) is auto mode: the scheduler stops when the timetable stops
 * getting better, at the latest at its own safety cap.
 */
public record GenerationRequested(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID jobId,
        UUID semesterId,
        Integer timeLimitSeconds,
        GenerationWeights weights,
        ProblemSnapshot problem) implements RozkladEvent {

    public GenerationRequested {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(semesterId, "semesterId");
        Objects.requireNonNull(problem, "problem");
        if (weights == null) {
            weights = GenerationWeights.DEFAULT;
        }
        if (timeLimitSeconds != null && timeLimitSeconds <= 0) {
            throw new IllegalArgumentException("timeLimitSeconds must be positive or absent");
        }
    }

    public GenerationRequested(EventEnvelope envelope, UUID jobId, UUID semesterId, Integer timeLimitSeconds,
                               GenerationWeights weights, ProblemSnapshot problem) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), jobId, semesterId, timeLimitSeconds,
                weights, problem);
    }

    /** Auto mode: no time limit in the request. */
    public boolean autoMode() {
        return timeLimitSeconds == null;
    }
}
