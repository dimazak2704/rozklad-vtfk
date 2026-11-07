package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** {@code generation.progress}: scheduler → core, roughly every 2 seconds while solving. */
public record GenerationProgress(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID jobId,
        long elapsedMs,
        long hardScore,
        long softScore) implements RozkladEvent {

    public GenerationProgress {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(jobId, "jobId");
    }

    public GenerationProgress(EventEnvelope envelope, UUID jobId, long elapsedMs, long hardScore, long softScore) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), jobId, elapsedMs, hardScore, softScore);
    }
}
