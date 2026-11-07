package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** {@code timetable.version.published}: core → notification. */
public record TimetableVersionPublished(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID versionId,
        UUID semesterId,
        LocalDate effectiveFrom,
        String name) implements RozkladEvent {

    public TimetableVersionPublished {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(semesterId, "semesterId");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    }

    public TimetableVersionPublished(EventEnvelope envelope, UUID versionId, UUID semesterId,
                                     LocalDate effectiveFrom, String name) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), versionId, semesterId, effectiveFrom, name);
    }
}
