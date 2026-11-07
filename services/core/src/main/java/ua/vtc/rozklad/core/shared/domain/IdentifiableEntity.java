package ua.vtc.rozklad.core.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Persistable;

/**
 * Entity whose UUID is assigned in Java when the object is created, so it is known before the
 * first flush and {@link #equals(Object)} is stable. Implements {@link Persistable} so that
 * Spring Data calls {@code persist} (not {@code merge}) for new instances.
 */
@MappedSuperclass
public abstract class IdentifiableEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = Ids.newId();

    @Transient
    private boolean persisted;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IdentifiableEntity entity)) {
            return false;
        }
        // getId() замість поля: у проксі Hibernate поля не ініціалізовані
        return Hibernate.getClass(this) == Hibernate.getClass(entity) && getId().equals(entity.getId());
    }

    @Override
    public final int hashCode() {
        return getId().hashCode();
    }

    @Override
    public String toString() {
        return Hibernate.getClass(this).getSimpleName() + "[id=" + getId() + "]";
    }
}
