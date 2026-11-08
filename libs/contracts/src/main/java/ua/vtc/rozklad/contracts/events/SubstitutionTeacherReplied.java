package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;

/**
 * {@code substitution.teacher-replied}: core → notification. The informed teacher of a NOTIFY change
 * answered and the answer left the status as it was: «Добре» ({@code CONFIRM}) on a CONFIRMED or
 * APPLIED change, or «Не можу» ({@code DECLINE}) on a change already APPLIED by a published order —
 * an objection the dispatcher has to settle with an amendment. «Не можу» before publication moves the
 * change to DECLINED and is announced by {@code substitution.status-changed} instead.
 */
public record SubstitutionTeacherReplied(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID changeId,
        UUID teacherId,
        ConfirmationDecision decision,
        ChangeStatus status,
        LocalDate date,
        int pair,
        String groupCode) implements RozkladEvent {

    public SubstitutionTeacherReplied {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(teacherId, "teacherId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(date, "date");
    }

    public SubstitutionTeacherReplied(EventEnvelope envelope, UUID changeId, UUID teacherId,
                                      ConfirmationDecision decision, ChangeStatus status, LocalDate date, int pair,
                                      String groupCode) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), changeId, teacherId, decision, status,
                date, pair, groupCode);
    }

    /** «Не можу» after the order was published: the change stays in force until an amendment. */
    public boolean raisesObjection() {
        return decision == ConfirmationDecision.DECLINE && status == ChangeStatus.APPLIED;
    }
}
