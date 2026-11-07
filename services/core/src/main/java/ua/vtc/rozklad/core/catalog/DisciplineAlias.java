package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Alternative spelling of a discipline («Укр.мова.», «НДКС»); {@code normalized} is unique. */
@Entity
@Table(name = "discipline_alias")
public class DisciplineAlias extends IdentifiableEntity {

    @Column(name = "discipline_id", nullable = false)
    private UUID disciplineId;

    @Column(name = "alias", nullable = false, length = 100)
    private String alias;

    @Column(name = "normalized", nullable = false, length = 100)
    private String normalized;

    protected DisciplineAlias() {
    }

    public DisciplineAlias(UUID disciplineId, String alias, String normalized) {
        this.disciplineId = Objects.requireNonNull(disciplineId, "disciplineId");
        this.alias = Objects.requireNonNull(alias, "alias");
        this.normalized = Objects.requireNonNull(normalized, "normalized");
    }

    public UUID getDisciplineId() {
        return disciplineId;
    }

    public String getAlias() {
        return alias;
    }

    public String getNormalized() {
        return normalized;
    }
}
