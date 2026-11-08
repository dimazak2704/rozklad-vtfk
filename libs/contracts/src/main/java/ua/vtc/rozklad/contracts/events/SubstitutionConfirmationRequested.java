package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;

/**
 * {@code substitution.confirmation-requested}: core → notification (Telegram message to the assigned
 * teacher). Nullable: roomName, startsAt/endsAt (no bell profile), deadline (no timeout; always null in
 * NOTIFY mode). {@code disciplineName} is the discipline the teacher will teach (the new one of a
 * REPLACE_DISCIPLINE change, otherwise the lesson's).
 *
 * <p>Additive (round 7, all nullable for events of older producers): {@code mode} (null = CONFIRM),
 * {@code kind} of the change, {@code originalDisciplineName} (the discipline of the lesson being
 * replaced) and {@code reasonCode} (core's reason code name, e.g. {@code MUTUAL}).
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
        Instant deadline,
        SubstitutionRequestMode mode,
        ChangeKind kind,
        String originalDisciplineName,
        String reasonCode) implements RozkladEvent {

    public SubstitutionConfirmationRequested {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(teacherId, "teacherId");
        Objects.requireNonNull(date, "date");
    }

    /** A CONFIRM request without the round 7 details (kept for producers and tests of the first schema). */
    public SubstitutionConfirmationRequested(EventEnvelope envelope, UUID changeId, UUID teacherId, LocalDate date,
                                             int pair, LocalTime startsAt, LocalTime endsAt, String groupCode,
                                             int subgroup, String disciplineName, String originalTeacherName,
                                             String roomName, Instant deadline) {
        this(envelope, changeId, teacherId, date, pair, startsAt, endsAt, groupCode, subgroup, disciplineName,
                originalTeacherName, roomName, deadline, null, null, null, null);
    }

    public SubstitutionConfirmationRequested(EventEnvelope envelope, UUID changeId, UUID teacherId, LocalDate date,
                                             int pair, LocalTime startsAt, LocalTime endsAt, String groupCode,
                                             int subgroup, String disciplineName, String originalTeacherName,
                                             String roomName, Instant deadline, SubstitutionRequestMode mode,
                                             ChangeKind kind, String originalDisciplineName, String reasonCode) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), changeId, teacherId, date, pair, startsAt,
                endsAt, groupCode, subgroup, disciplineName, originalTeacherName, roomName, deadline, mode, kind,
                originalDisciplineName, reasonCode);
    }

    /** The mode with the default of older producers applied. */
    public SubstitutionRequestMode effectiveMode() {
        return mode == null ? SubstitutionRequestMode.CONFIRM : mode;
    }
}
