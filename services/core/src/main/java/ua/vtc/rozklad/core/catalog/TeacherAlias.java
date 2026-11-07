package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Alternative spelling of a teacher («Граболінська»); {@code normalized} (DOMAIN §10) is unique. */
@Entity
@Table(name = "teacher_alias")
public class TeacherAlias extends IdentifiableEntity {

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    @Column(name = "alias", nullable = false, length = 100)
    private String alias;

    @Column(name = "normalized", nullable = false, length = 100)
    private String normalized;

    protected TeacherAlias() {
    }

    public TeacherAlias(UUID teacherId, String alias, String normalized) {
        this.teacherId = Objects.requireNonNull(teacherId, "teacherId");
        this.alias = Objects.requireNonNull(alias, "alias");
        this.normalized = Objects.requireNonNull(normalized, "normalized");
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public String getAlias() {
        return alias;
    }

    public String getNormalized() {
        return normalized;
    }
}
