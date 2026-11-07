package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** {@code generation.cancel}: core → scheduler. */
public record GenerationCancelRequested(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID jobId) implements RozkladEvent {

    public GenerationCancelRequested {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(jobId, "jobId");
    }

    public GenerationCancelRequested(EventEnvelope envelope, UUID jobId) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), jobId);
    }
}
