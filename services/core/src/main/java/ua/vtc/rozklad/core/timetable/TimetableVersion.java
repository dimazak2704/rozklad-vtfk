package ua.vtc.rozklad.core.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Версія базового розкладу (weekly template). {@code metrics} is a JSON snapshot of
 * {@code MetricsDto}; replace the node instead of mutating it in place.
 */
@Entity
@Table(name = "timetable_version")
public class TimetableVersion extends BaseEntity {

    @Column(name = "semester_id", nullable = false)
    private UUID semesterId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VersionStatus status = VersionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private VersionSource source = VersionSource.MANUAL;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by", length = 100)
    private String publishedBy;

    @Column(name = "generation_job_id")
    private UUID generationJobId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", columnDefinition = "jsonb")
    private JsonNode metrics;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    protected TimetableVersion() {
    }

    public TimetableVersion(UUID semesterId, String name, VersionSource source) {
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        setName(name);
        this.source = Objects.requireNonNull(source, "source");
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public VersionStatus getStatus() {
        return status;
    }

    public boolean isDraft() {
        return status == VersionStatus.DRAFT;
    }

    public VersionSource getSource() {
        return source;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    /** DRAFT → PUBLISHED; publication rules (conflicts, archiving the previous version) live in the service. */
    public void markPublished(LocalDate effectiveFrom, Instant publishedAt, String publishedBy) {
        if (status != VersionStatus.DRAFT) {
            throw new IllegalStateException("Only a draft version can be published, current status: " + status);
        }
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        this.publishedBy = publishedBy;
        this.status = VersionStatus.PUBLISHED;
    }

    public void markArchived() {
        this.status = VersionStatus.ARCHIVED;
    }

    public UUID getGenerationJobId() {
        return generationJobId;
    }

    public void setGenerationJobId(UUID generationJobId) {
        this.generationJobId = generationJobId;
    }

    public JsonNode getMetrics() {
        return metrics;
    }

    public void setMetrics(JsonNode metrics) {
        this.metrics = metrics;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
