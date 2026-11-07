package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeStatus;

/**
 * {@code substitution.status-changed}: core → notification (edit bot message, notify admins).
 * {@code teacherId} is the newly assigned teacher, may be {@code null} for kinds without one.
 */
public record SubstitutionStatusChanged(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID changeId,
        ChangeStatus status,
        UUID teacherId,
        LocalDate date,
        String groupCode) implements RozkladEvent {

    public SubstitutionStatusChanged {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(status, "status");
    }

    public SubstitutionStatusChanged(EventEnvelope envelope, UUID changeId, ChangeStatus status, UUID teacherId,
                                     LocalDate date, String groupCode) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), changeId, status, teacherId, date,
                groupCode);
    }
}
