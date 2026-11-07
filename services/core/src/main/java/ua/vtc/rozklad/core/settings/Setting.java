package ua.vtc.rozklad.core.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import tools.jackson.databind.JsonNode;

/**
 * System setting stored as JSON under a dotted key (e.g. {@code substitution.approvalMode},
 * {@code accounting.hoursPerPair}). {@code save()} of a setting with an existing key updates it.
 */
@Entity
@Table(name = "setting")
@EntityListeners(AuditingEntityListener.class)
public class Setting {

    @Id
    @Column(name = "key", nullable = false, length = 100, updatable = false)
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false, columnDefinition = "jsonb")
    private JsonNode value;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    protected Setting() {
    }

    public Setting(String key, JsonNode value, String updatedBy) {
        this.key = Objects.requireNonNull(key, "key");
        update(value, updatedBy);
    }

    public String getKey() {
        return key;
    }

    public JsonNode getValue() {
        return value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void update(JsonNode value, String updatedBy) {
        this.value = Objects.requireNonNull(value, "value");
        this.updatedBy = updatedBy;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Setting setting && key.equals(setting.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }
}
