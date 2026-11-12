package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;

/**
 * {@code substitution.details-changed}: core → notification. What the assigned teacher reads about a substitution
 * he does not have to answer any more changed: today the dispatcher gave a new room to a CONFIRM request the
 * teacher (or the dispatcher for him) already confirmed. Information only: nothing is asked, the change stays
 * CONFIRMED and keeps the teacher's answer. A pending request or a NOTIFY notice in force is re-sent as
 * {@code substitution.confirmation-requested} instead.
 *
 * <p>The details are the current ones, with the meaning of the same fields of
 * {@link SubstitutionConfirmationRequested}. Nullable: startsAt/endsAt (no bell profile), groupCode,
 * disciplineName, originalTeacherName, roomName (no room), kind, originalDisciplineName, reasonCode.
 *
 * <p>A separate routing key on purpose: a consumer that does not know it never receives it (the queue is not
 * bound to it), or dead-letters it as unknown. Re-using {@code confirmation-requested} would make an older
 * notification reopen the confirmed request and ask the teacher to confirm a second time.
 */
public record SubstitutionDetailsChanged(
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
        ChangeKind kind,
        String originalDisciplineName,
        String reasonCode) implements RozkladEvent {

    public SubstitutionDetailsChanged {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(teacherId, "teacherId");
        Objects.requireNonNull(date, "date");
    }

    public SubstitutionDetailsChanged(EventEnvelope envelope, UUID changeId, UUID teacherId, LocalDate date, int pair,
                                      LocalTime startsAt, LocalTime endsAt, String groupCode, int subgroup,
                                      String disciplineName, String originalTeacherName, String roomName,
                                      ChangeKind kind, String originalDisciplineName, String reasonCode) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), changeId, teacherId, date, pair, startsAt,
                endsAt, groupCode, subgroup, disciplineName, originalTeacherName, roomName, kind,
                originalDisciplineName, reasonCode);
    }
}
