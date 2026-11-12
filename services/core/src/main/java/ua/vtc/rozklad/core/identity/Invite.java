package ua.vtc.rozklad.core.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Registration invite of a teacher, or a password link of an account ({@link #forAccount}). Only the SHA-256
 * hex of the token is stored; the token itself appears once in the link ({@code /invite/{token}} or
 * {@code /password/{token}}). It is used at most once, expires, and is revoked when the admin issues a newer
 * one for the same target or revokes the access. Optimistic locking prevents a double accept. Exactly one of
 * {@code teacherId} and {@code accountId} is set.
 */
@Entity
@Table(name = "invite")
public class Invite extends BaseEntity {

    @Column(name = "teacher_id", updatable = false)
    private UUID teacherId;

    /** Target of a password link: an account of the sign-in service. */
    @Column(name = "account_id", updatable = false)
    private UUID accountId;

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

    /**
     * A password link: the person of {@code accountId} sets a (new) password. Same token, lifetime and
     * revocation rules as an invite.
     */
    public static Invite forAccount(UUID accountId, String tokenHash, Instant expiresAt, String createdBy) {
        Invite link = new Invite();
        link.accountId = Objects.requireNonNull(accountId, "accountId");
        link.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        link.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        link.createdBy = createdBy;
        return link;
    }

    /** {@code null} for a password link. */
    public UUID getTeacherId() {
        return teacherId;
    }

    /** {@code null} for a teacher invite. */
    public UUID getAccountId() {
        return accountId;
    }

    public boolean isPasswordLink() {
        return accountId != null;
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
        return "Invite[id=" + getId() + ", teacherId=" + teacherId + ", accountId=" + accountId + "]";
    }
}
