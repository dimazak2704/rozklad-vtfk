package ua.vtc.rozklad.notification.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * E-mail settings of one account (token {@code sub}): the master switch and the kinds that deviate from their
 * default. Unknown kinds in {@code overrides} (written by a newer version) are ignored.
 */
@Entity
@Table(name = "email_preference")
public class EmailPreference implements Persistable<UUID> {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "overrides", nullable = false, columnDefinition = "jsonb")
    private Map<String, Boolean> overrides = new LinkedHashMap<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean persisted;

    protected EmailPreference() {
    }

    EmailPreference(UUID accountId, Instant now) {
        this.accountId = accountId;
        this.updatedAt = now;
    }

    /** The settings of an account that never saved any: every default, the master switch on. */
    static EmailPreference defaults(UUID accountId) {
        return new EmailPreference(accountId, Instant.EPOCH);
    }

    /** Whether {@code kind} is on for this account (a kind that cannot be switched is always on). */
    public boolean allows(EmailKind kind) {
        if (!kind.switchable()) {
            return true;
        }
        if (!enabled) {
            return false;
        }
        Boolean override = overrides.get(kind.name());
        return override != null ? override : kind.defaultOn();
    }

    /** Stores only deviations from the defaults; a value equal to the default removes the override. */
    void update(boolean enabled, Map<EmailKind, Boolean> kinds, Instant now) {
        this.enabled = enabled;
        Map<String, Boolean> next = new LinkedHashMap<>(overrides);
        kinds.forEach((kind, on) -> {
            if (on == kind.defaultOn()) {
                next.remove(kind.name());
            } else {
                next.put(kind.name(), on);
            }
        });
        this.overrides = next;
        this.updatedAt = now;
    }

    @Override
    public UUID getId() {
        return accountId;
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

    public UUID getAccountId() {
        return accountId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Boolean> getOverrides() {
        return Map.copyOf(overrides);
    }

    public long getVersion() {
        return version;
    }
}
