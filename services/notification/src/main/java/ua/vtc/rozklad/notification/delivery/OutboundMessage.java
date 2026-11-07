package ua.vtc.rozklad.notification.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.shared.IdentifiableEntity;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;

/**
 * One queued Telegram message and, after delivery, its log entry. Status machine:
 * QUEUED → SENT | RETRYING → … → SENT | FAILED.
 */
@Entity
@Table(name = "outbound_message")
public class OutboundMessage extends IdentifiableEntity {

    static final int MAX_ERROR_LENGTH = 1000;

    @Column(name = "queue_seq", insertable = false, updatable = false)
    private Long queueSeq;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Column(name = "binding_id")
    private UUID bindingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "binding_type", length = 20)
    private BindingType bindingType;

    @Column(name = "chat_title", length = 200)
    private String chatTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 40, updatable = false)
    private DeliveryPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private OutboundKind kind;

    @Column(name = "text", columnDefinition = "text", updatable = false)
    private String text;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reply_markup", columnDefinition = "jsonb", updatable = false)
    private InlineKeyboard replyMarkup;

    @Column(name = "media_ref", length = 100, updatable = false)
    private String mediaRef;

    @Column(name = "edit_message_id", updatable = false)
    private Long editMessageId;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    @Column(name = "dedup_key", length = 200, updatable = false)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboundStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboundMessage() {
    }

    OutboundMessage(OutboundDraft draft, String text, InlineKeyboard keyboard, String dedupKey, Instant now) {
        this.chatId = draft.recipient().chatId();
        this.bindingId = draft.recipient().bindingId();
        this.bindingType = draft.recipient().bindingType();
        this.chatTitle = draft.recipient().title();
        this.purpose = draft.purpose();
        this.kind = draft.kind();
        this.text = text;
        this.replyMarkup = keyboard;
        this.mediaRef = draft.mediaRef();
        this.editMessageId = draft.editMessageId();
        this.correlationId = draft.correlationId();
        this.orderId = draft.orderId();
        this.eventId = draft.eventId();
        this.dedupKey = dedupKey;
        this.status = OutboundStatus.QUEUED;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void lease(Instant until, Instant now) {
        this.lockedUntil = until;
        this.updatedAt = now;
    }

    void markSent(Long telegramMessageId, Instant now) {
        this.status = OutboundStatus.SENT;
        this.attempts++;
        this.telegramMessageId = telegramMessageId;
        this.sentAt = now;
        this.lockedUntil = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    void markRetry(String error, Instant nextAttempt, boolean countsAsAttempt, Instant now) {
        this.status = OutboundStatus.RETRYING;
        if (countsAsAttempt) {
            this.attempts++;
        }
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttempt;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    void markFailed(String error, boolean countsAsAttempt, Instant now) {
        this.status = OutboundStatus.FAILED;
        if (countsAsAttempt) {
            this.attempts++;
        }
        this.lastError = truncate(error);
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    /** Rate limited: try again later without spending an attempt. */
    void defer(Instant until, Instant now) {
        this.nextAttemptAt = until;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    /** The group became a supergroup: same message, new chat id. */
    void retarget(long newChatId, Instant now) {
        this.chatId = newChatId;
        this.nextAttemptAt = now;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }

    public long getChatId() {
        return chatId;
    }

    public UUID getBindingId() {
        return bindingId;
    }

    public BindingType getBindingType() {
        return bindingType;
    }

    public String getChatTitle() {
        return chatTitle;
    }

    public DeliveryPurpose getPurpose() {
        return purpose;
    }

    public OutboundKind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    public InlineKeyboard getReplyMarkup() {
        return replyMarkup;
    }

    public String getMediaRef() {
        return mediaRef;
    }

    public Long getEditMessageId() {
        return editMessageId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public OutboundStatus getStatus() {
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

    public Long getTelegramMessageId() {
        return telegramMessageId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
