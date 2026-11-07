package ua.vtc.rozklad.notification.binding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.notification.shared.IdentifiableEntity;

/** One-time binding code; only its SHA-256 is stored. */
@Entity
@Table(name = "binding_code")
public class BindingCode extends IdentifiableEntity {

    @Column(name = "code_hash", nullable = false, length = 64, updatable = false)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private BindingType type;

    @Column(name = "group_id", updatable = false)
    private UUID groupId;

    @Column(name = "group_code", length = 20, updatable = false)
    private String groupCode;

    @Column(name = "teacher_id", updatable = false)
    private UUID teacherId;

    @Column(name = "label", nullable = false, length = 200, updatable = false)
    private String label;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_chat_id")
    private Long usedChatId;

    protected BindingCode() {
    }

    BindingCode(String codeHash, BindingTarget target, String createdBy, Instant createdAt, Instant expiresAt) {
        this.codeHash = Objects.requireNonNull(codeHash, "codeHash");
        this.type = target.type();
        this.groupId = target.groupId();
        this.groupCode = target.groupCode();
        this.teacherId = target.teacherId();
        this.label = target.label();
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    void markUsed(long chatId, Instant now) {
        if (isUsed()) {
            throw new IllegalStateException("binding code already used");
        }
        this.usedAt = now;
        this.usedChatId = chatId;
    }

    public BindingTarget target() {
        return new BindingTarget(type, groupId, groupCode, teacherId, label);
    }

    public BindingType getType() {
        return type;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
