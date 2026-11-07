package ua.vtc.rozklad.core.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Timetable generation job as seen by core: request {@code params} (JSON), live {@code progress}
 * points ({@code [{t, hard, soft}]}), result version. The scheduler keeps its own job table.
 */
@Entity
@Table(name = "generation_job")
public class GenerationJob extends BaseEntity {

    @Column(name = "semester_id", nullable = false, updatable = false)
    private UUID semesterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GenerationStatus status = GenerationStatus.QUEUED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false, columnDefinition = "jsonb")
    private JsonNode params;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "best_score", length = 64)
    private String bestScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress", nullable = false, columnDefinition = "jsonb")
    private JsonNode progress = JsonNodeFactory.instance.arrayNode();

    @Column(name = "result_version_id")
    private UUID resultVersionId;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    protected GenerationJob() {
    }

    public GenerationJob(UUID semesterId, JsonNode params, String requestedBy) {
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        this.params = Objects.requireNonNull(params, "params");
        this.requestedBy = requestedBy;
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public GenerationStatus getStatus() {
        return status;
    }

    public void setStatus(GenerationStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public JsonNode getParams() {
        return params;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getBestScore() {
        return bestScore;
    }

    public void setBestScore(String bestScore) {
        this.bestScore = bestScore;
    }

    public JsonNode getProgress() {
        return progress;
    }

    /** Replace the whole array (in-place mutation is not detected by dirty checking). */
    public void setProgress(JsonNode progress) {
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    public UUID getResultVersionId() {
        return resultVersionId;
    }

    public void setResultVersionId(UUID resultVersionId) {
        this.resultVersionId = resultVersionId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getRequestedBy() {
        return requestedBy;
    }
}
