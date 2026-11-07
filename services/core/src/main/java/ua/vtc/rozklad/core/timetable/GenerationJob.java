package ua.vtc.rozklad.core.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Timetable generation job as seen by core: request {@code params} (JSON), live {@code progress}
 * points ({@code [{t, hard, soft}]}, {@code t} in seconds), result version. {@code plan} keeps the
 * units sent to the scheduler so that its assignments can be turned into template lessons;
 * {@code analysis}, {@code metricsBefore} and {@code metricsAfter} are filled on completion.
 * The scheduler keeps its own job table.
 */
@Entity
@Table(name = "generation_job")
public class GenerationJob extends BaseEntity {

    private static final Set<GenerationStatus> ACTIVE = Set.of(GenerationStatus.QUEUED, GenerationStatus.RUNNING);

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

    @Column(name = "base_version_id")
    private UUID baseVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan", columnDefinition = "jsonb")
    private JsonNode plan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis", columnDefinition = "jsonb")
    private JsonNode analysis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_before", columnDefinition = "jsonb")
    private JsonNode metricsBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_after", columnDefinition = "jsonb")
    private JsonNode metricsAfter;

    @Column(name = "last_progress_at")
    private Instant lastProgressAt;

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

    /** QUEUED or RUNNING: the scheduler may still send progress or a result. */
    public boolean isActive() {
        return ACTIVE.contains(status);
    }

    /** Progress from the scheduler: the first one moves QUEUED → RUNNING. Times come from core's clock. */
    public void recordProgress(Instant receivedAt, JsonNode progressPoints, String bestScore) {
        requireActive();
        if (status == GenerationStatus.QUEUED) {
            status = GenerationStatus.RUNNING;
            startedAt = receivedAt;
        }
        this.progress = Objects.requireNonNull(progressPoints, "progressPoints");
        this.bestScore = bestScore;
        this.lastProgressAt = receivedAt;
    }

    /** Terminal transition (SOLVED, FAILED or CANCELLED); the job no longer accepts scheduler events. */
    public void finish(GenerationStatus terminalStatus, Instant finishedAt, String error) {
        requireActive();
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("status must be terminal: " + terminalStatus);
        }
        this.status = terminalStatus;
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
        if (startedAt == null && terminalStatus == GenerationStatus.SOLVED) {
            startedAt = finishedAt;
        }
        this.error = error;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("Generation job " + getId() + " is already " + status);
        }
    }

    public UUID getBaseVersionId() {
        return baseVersionId;
    }

    public void setBaseVersionId(UUID baseVersionId) {
        this.baseVersionId = baseVersionId;
    }

    public JsonNode getPlan() {
        return plan;
    }

    public void setPlan(JsonNode plan) {
        this.plan = plan;
    }

    public JsonNode getAnalysis() {
        return analysis;
    }

    public void setAnalysis(JsonNode analysis) {
        this.analysis = analysis;
    }

    public JsonNode getMetricsBefore() {
        return metricsBefore;
    }

    public void setMetricsBefore(JsonNode metricsBefore) {
        this.metricsBefore = metricsBefore;
    }

    public JsonNode getMetricsAfter() {
        return metricsAfter;
    }

    public void setMetricsAfter(JsonNode metricsAfter) {
        this.metricsAfter = metricsAfter;
    }

    public Instant getLastProgressAt() {
        return lastProgressAt;
    }
}
