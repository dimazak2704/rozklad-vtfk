package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code substitution.confirmation-requested}: core → notification (Telegram request to the
 * teacher). Nullable: roomName, startsAt/endsAt (no bell profile), deadline (no timeout).
 */
public record SubstitutionConfirmationRequested(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID changeId,
        UUID teacherId,
        LocalDate date,
        int pair,
        LocalTime startsAt,
        LocalTime endsAt,
        String groupCode,
        int subgroup,
        String disciplineName,
        String originalTeacherName,
        String roomName,
        Instant deadline) implements RozkladEvent {

    public SubstitutionConfirmationRequested {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(teacherId, "teacherId");
        Objects.requireNonNull(date, "date");
    }

    public SubstitutionConfirmationRequested(EventEnvelope envelope, UUID changeId, UUID teacherId, LocalDate date,
                                             int pair, LocalTime startsAt, LocalTime endsAt, String groupCode,
                                             int subgroup, String disciplineName, String originalTeacherName,
                                             String roomName, Instant deadline) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), changeId, teacherId, date, pair, startsAt,
                endsAt, groupCode, subgroup, disciplineName, originalTeacherName, roomName, deadline);
    }
}
