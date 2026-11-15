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
import ua.vtc.rozklad.notification.shared.IdentifiableEntity;

/**
 * One letter to one person and, after sending, its log entry. Rendered when the intent was expanded, so the log
 * shows exactly what went out.
 */
@Entity
@Table(name = "email_message")
public class EmailMessage extends IdentifiableEntity {

    static final int MAX_ERROR_LENGTH = 1000;

    @Column(name = "notice_id", nullable = false, updatable = false)
    private UUID noticeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 40, updatable = false)
    private EmailKind kind;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "account_name", length = 200, updatable = false)
    private String accountName;

    @Column(name = "teacher_id", updatable = false)
    private UUID teacherId;

    @Column(name = "to_address", nullable = false, length = 254, updatable = false)
    private String toAddress;

    @Column(name = "subject", nullable = false, length = 250, updatable = false)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "text", updatable = false)
    private String bodyHtml;

    @Column(name = "body_text", nullable = false, columnDefinition = "text", updatable = false)
    private String bodyText;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "lesson_date", updatable = false)
    private LocalDate lessonDate;

    @Column(name = "lesson_ends_at", updatable = false)
    private LocalTime lessonEndsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EmailMessageStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "not_before", nullable = false, updatable = false)
    private Instant notBefore;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "smtp_message_id", length = 250)
    private String smtpMessageId;

    @Column(name = "dedup_key", nullable = false, length = 200, updatable = false)
    private String dedupKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected EmailMessage() {
    }

    EmailMessage(EmailNotice notice, EmailKind kind, Recipient recipient, MailTemplates.Rendered letter,
                 Instant notBefore, Instant now) {
        this.noticeId = notice.getId();
        this.kind = kind;
        this.accountId = recipient.accountId();
        this.accountName = recipient.name() == null ? null : MailHeaders.clean(recipient.name(), 200);
        this.teacherId = notice.getTeacherId();
        this.toAddress = recipient.address();
        this.subject = letter.subject();
        this.bodyHtml = letter.html();
        this.bodyText = letter.text();
        this.correlationId = notice.getCorrelationId();
        this.lessonDate = notice.getLessonDate();
        this.lessonEndsAt = notice.getLessonEndsAt();
        this.status = EmailMessageStatus.QUEUED;
        this.notBefore = notBefore;
        this.nextAttemptAt = notBefore;
        this.dedupKey = notice.getId() + ":" + recipient.accountId();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A person a letter goes to: the account, its display name and the verified address. */
    public record Recipient(UUID accountId, String name, String address) {
    }

    void lease(Instant until, Instant now) {
        this.lockedUntil = until;
        this.updatedAt = now;
    }

    void markSent(String smtpMessageId, Instant now) {
        this.status = EmailMessageStatus.SENT;
        this.attempts++;
        this.smtpMessageId = smtpMessageId;
        this.sentAt = now;
        this.lockedUntil = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    void markRetry(String error, Instant nextAttempt, Instant now) {
        this.status = EmailMessageStatus.RETRYING;
        this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttempt;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    void markFailed(String error, boolean countsAsAttempt, Instant now) {
        this.status = EmailMessageStatus.FAILED;
        if (countsAsAttempt) {
            this.attempts++;
        }
        this.lastError = truncate(error);
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    void markSuperseded(Instant now) {
        this.status = EmailMessageStatus.SUPERSEDED;
        this.lastError = "Замінено новішим листом про ту саму заміну.";
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    /** Rate limited: later, without spending an attempt. */
    void defer(Instant until, Instant now) {
        this.nextAttemptAt = until;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    /** Claimed by a worker right now (its SMTP call may be under way). */
    boolean isLeased(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }

    public UUID getNoticeId() {
        return noticeId;
    }

    public EmailKind getKind() {
        return kind;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getAccountName() {
        return accountName;
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public String getToAddress() {
        return toAddress;
    }

    public String getSubject() {
        return subject;
    }

    public String getBodyHtml() {
        return bodyHtml;
    }

    public String getBodyText() {
        return bodyText;
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

    public EmailMessageStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getSmtpMessageId() {
        return smtpMessageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
