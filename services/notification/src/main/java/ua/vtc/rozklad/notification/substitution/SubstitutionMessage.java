package ua.vtc.rozklad.notification.substitution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ua.vtc.rozklad.notification.shared.IdentifiableEntity;

/** A sent request/reminder message whose text is edited to the final state later. */
@Entity
@Table(name = "substitution_message")
public class SubstitutionMessage extends IdentifiableEntity {

    @Column(name = "change_id", nullable = false, updatable = false)
    private UUID changeId;

    @Column(name = "chat_id", nullable = false, updatable = false)
    private long chatId;

    @Column(name = "message_id", nullable = false, updatable = false)
    private long messageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SubstitutionMessage() {
    }

    SubstitutionMessage(UUID changeId, long chatId, long messageId, Instant createdAt) {
        this.changeId = changeId;
        this.chatId = chatId;
        this.messageId = messageId;
        this.createdAt = createdAt;
    }

    public UUID getChangeId() {
        return changeId;
    }

    public long getChatId() {
        return chatId;
    }

    public long getMessageId() {
        return messageId;
    }
}
