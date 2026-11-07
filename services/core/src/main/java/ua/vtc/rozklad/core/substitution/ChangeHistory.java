package ua.vtc.rozklad.core.substitution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Append-only status history of a change ({@code fromStatus} is {@code null} for the first entry). */
@Entity
@Table(name = "change_history")
public class ChangeHistory extends IdentifiableEntity {

    @Column(name = "change_id", nullable = false, updatable = false)
    private UUID changeId;

    @Column(name = "at", nullable = false, updatable = false)
    private Instant at;

    @Column(name = "actor", length = 100, updatable = false)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 24, updatable = false)
    private ChangeStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 24, updatable = false)
    private ChangeStatus toStatus;

    @Column(name = "comment", columnDefinition = "text", updatable = false)
    private String comment;

    protected ChangeHistory() {
    }

    public ChangeHistory(UUID changeId, Instant at, String actor, ChangeStatus fromStatus, ChangeStatus toStatus,
                         String comment) {
        this.changeId = Objects.requireNonNull(changeId, "changeId");
        this.at = Objects.requireNonNull(at, "at");
        this.actor = actor;
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus");
        this.comment = comment;
    }

    public UUID getChangeId() {
        return changeId;
    }

    public Instant getAt() {
        return at;
    }

    public String getActor() {
        return actor;
    }

    public ChangeStatus getFromStatus() {
        return fromStatus;
    }

    public ChangeStatus getToStatus() {
        return toStatus;
    }

    public String getComment() {
        return comment;
    }
}
