package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** {@code generation.requested}: core → scheduler. {@code weights == null} means defaults. */
public record GenerationRequested(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID jobId,
        UUID semesterId,
        int timeLimitSeconds,
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
        if (timeLimitSeconds <= 0) {
            throw new IllegalArgumentException("timeLimitSeconds must be positive");
        }
    }

    public GenerationRequested(EventEnvelope envelope, UUID jobId, UUID semesterId, int timeLimitSeconds,
                               GenerationWeights weights, ProblemSnapshot problem) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), jobId, semesterId, timeLimitSeconds,
                weights, problem);
    }
}
