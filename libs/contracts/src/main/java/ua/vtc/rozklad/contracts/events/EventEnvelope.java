package ua.vtc.rozklad.contracts.events;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope values of a new event. Producers create it with {@link #create(Clock)} and pass it to
 * the convenience constructor of the event record.
 */
public record EventEnvelope(UUID eventId, Instant occurredAt, int version) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    public static EventEnvelope create(Clock clock) {
        return new EventEnvelope(UUID.randomUUID(), clock.instant(), RozkladEvents.SCHEMA_VERSION);
    }
}
