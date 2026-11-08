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
 * appears once in the invite link ({@code /invite/{token}}). An invite is used at most once,
 * expires, and is revoked when the admin issues a newer one for the same teacher. Optimistic
 * locking prevents a double accept.
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

    @Column(name = "revoked_at")
    private Instant revokedAt;

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

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    /** USED and REVOKED are final; otherwise ACTIVE until {@code expiresAt}, then EXPIRED. */
    public InviteStatus status(Instant now) {
        if (usedAt != null) {
            return InviteStatus.USED;
        }
        if (revokedAt != null) {
            return InviteStatus.REVOKED;
        }
        return now.isBefore(expiresAt) ? InviteStatus.ACTIVE : InviteStatus.EXPIRED;
    }

    public boolean isUsable(Instant now) {
        return status(now) == InviteStatus.ACTIVE;
    }

    public void markUsed(Instant now) {
        if (usedAt != null || revokedAt != null) {
            throw new IllegalStateException("Invite is already used or revoked");
        }
        this.usedAt = Objects.requireNonNull(now, "now");
    }

    /** No-op for an invite that is already used or revoked. */
    public void revoke(Instant now) {
        if (usedAt == null && revokedAt == null) {
            this.revokedAt = Objects.requireNonNull(now, "now");
        }
    }

    @Override
    public String toString() {
        return "Invite[id=" + getId() + ", teacherId=" + teacherId + "]";
    }
}
