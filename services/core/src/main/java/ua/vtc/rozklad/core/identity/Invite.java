package ua.vtc.rozklad.core.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Registration invite of a teacher. Only the SHA-256 hex of the token is stored; the token itself
 * appears once in the invite link ({@code /invite/{token}}). Optimistic locking prevents a double
 * accept.
 */
@Entity
@Table(name = "invite")
public class Invite extends BaseEntity {

    @Column(name = "teacher_id", nullable = false, updatable = false)
    private UUID teacherId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    protected Invite() {
    }

    public Invite(UUID teacherId, String tokenHash, Instant expiresAt, String createdBy) {
        this.teacherId = Objects.requireNonNull(teacherId, "teacherId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.createdBy = createdBy;
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        if (usedAt != null) {
            throw new IllegalStateException("Invite is already used");
        }
        this.usedAt = Objects.requireNonNull(now, "now");
    }

    @Override
    public String toString() {
        return "Invite[id=" + getId() + ", teacherId=" + teacherId + "]";
    }
}
