package ua.vtc.rozklad.notification.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ua.vtc.rozklad.notification.shared.IdentifiableEntity;

/**
 * An intent to e-mail somebody about an event, written in the event's transaction (no HTTP call there). The
 * worker later resolves the people ({@link EmailExpander}) and turns it into letters.
 */
@Entity
@Table(name = "email_notice")
public class EmailNotice extends IdentifiableEntity {

    static final int MAX_ERROR_LENGTH = 1000;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 40, updatable = false)
    private EmailKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false, length = 16, updatable = false)
    private EmailNoticeTarget target;

    @Column(name = "teacher_id", updatable = false)
    private UUID teacherId;

    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "lesson_date", updatable = false)
    private LocalDate lessonDate;

    @Column(name = "lesson_ends_at", updatable = false)
    private LocalTime lessonEndsAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String model;

    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    @Column(name = "dedup_key", nullable = false, length = 200, updatable = false)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EmailNoticeStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmailNotice() {
    }

    EmailNotice(EmailKind kind, EmailNoticeTarget target, UUID teacherId, UUID accountId, UUID correlationId,
                LocalDate lessonDate, LocalTime lessonEndsAt, String model, UUID eventId, String dedupKey,
                Instant now) {
        this.kind = kind;
        this.target = target;
        this.teacherId = teacherId;
        this.accountId = accountId;
        this.correlationId = correlationId;
        this.lessonDate = lessonDate;
        this.lessonEndsAt = lessonEndsAt;
        this.model = model;
        this.eventId = eventId;
        this.dedupKey = dedupKey;
        this.status = EmailNoticeStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void lease(Instant until, Instant now) {
        this.lockedUntil = until;
        this.updatedAt = now;
    }

    /** Letters written; {@code note} says who got none and why (no addresses), for diagnosis. */
    void markExpanded(String note, Instant now) {
        this.status = EmailNoticeStatus.EXPANDED;
        this.attempts++;
        this.lastError = truncate(note);
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    void markRetry(String error, Instant nextAttempt, Instant now) {
        this.status = EmailNoticeStatus.RETRYING;
        this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttempt;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    void markFailed(String error, Instant now) {
        this.status = EmailNoticeStatus.FAILED;
        this.attempts++;
        this.lastError = truncate(error);
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }

    public EmailKind getKind() {
        return kind;
    }

    public EmailNoticeTarget getTarget() {
        return target;
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public LocalDate getLessonDate() {
        return lessonDate;
    }

    public LocalTime getLessonEndsAt() {
        return lessonEndsAt;
    }

    public String getModel() {
        return model;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public EmailNoticeStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
