package ua.vtc.rozklad.scheduler.generation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.contracts.GenerationStatus;

/**
 * A generation job of the scheduler, identified by core's {@code jobId}: QUEUED → RUNNING →
 * SOLVED | FAILED | CANCELLED. The finished job keeps its {@code generation.completed} payload
 * until the broker has confirmed it ({@code completionSentAt}), so a lost publication is retried.
 */
@Entity
@Table(name = "generation_job")
public class GenerationJob implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "semester_id", updatable = false)
    private UUID semesterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GenerationStatus status;

    @Column(name = "time_limit_seconds", updatable = false)
    private Integer timeLimitSeconds;

    @Column(name = "unit_count", updatable = false)
    private Integer unitCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weights", columnDefinition = "jsonb", updatable = false)
    private JsonNode weights;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "best_hard_score")
    private Long bestHardScore;

    @Column(name = "best_soft_score")
    private Long bestSoftScore;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "completion", columnDefinition = "jsonb")
    private JsonNode completion;

    @Column(name = "completion_sent_at")
    private Instant completionSentAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean newEntity;

    protected GenerationJob() {
    }

    private GenerationJob(UUID id, UUID semesterId, Integer timeLimitSeconds, Integer unitCount, JsonNode weights,
                          Instant receivedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.semesterId = semesterId;
        this.timeLimitSeconds = timeLimitSeconds;
        this.unitCount = unitCount;
        this.weights = weights;
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.status = GenerationStatus.QUEUED;
        this.newEntity = true;
    }

    /** A job accepted from {@code generation.requested}. */
    public static GenerationJob requested(UUID id, UUID semesterId, int timeLimitSeconds, int unitCount,
                                          JsonNode weights, Instant receivedAt) {
        return new GenerationJob(id, Objects.requireNonNull(semesterId, "semesterId"), timeLimitSeconds, unitCount,
                weights, receivedAt);
    }

    /** A job known only from {@code generation.cancel} that arrived before its request. */
    public static GenerationJob unknown(UUID id, Instant receivedAt) {
        return new GenerationJob(id, null, null, null, null, receivedAt);
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public boolean isActive() {
        return !status.isTerminal();
    }

    /** QUEUED → RUNNING; {@code false} when the job is no longer queued. */
    public boolean start(Instant now) {
        if (status != GenerationStatus.QUEUED) {
            return false;
        }
        status = GenerationStatus.RUNNING;
        startedAt = now;
        return true;
    }

    public void requestCancel() {
        if (!isActive()) {
            throw new IllegalStateException("job " + id + " is already " + status);
        }
        cancelRequested = true;
    }

    /** Ends an active job with a terminal status and the event that announces it. */
    public void finish(GenerationStatus result, Long hardScore, Long softScore, String failure,
                       JsonNode completionEvent, Instant now) {
        if (!isActive()) {
            throw new IllegalStateException("job " + id + " is already " + status);
        }
        if (!result.isTerminal()) {
            throw new IllegalArgumentException("not a terminal status: " + result);
        }
        status = result;
        bestHardScore = hardScore;
        bestSoftScore = softScore;
        error = failure;
        completion = Objects.requireNonNull(completionEvent, "completionEvent");
        finishedAt = now;
    }

    public void markCompletionSent(Instant now) {
        completionSentAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public GenerationStatus getStatus() {
        return status;
    }

    public Integer getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    public Integer getUnitCount() {
        return unitCount;
    }

    public JsonNode getWeights() {
        return weights;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Long getBestHardScore() {
        return bestHardScore;
    }

    public Long getBestSoftScore() {
        return bestSoftScore;
    }

    public Long getElapsedMs() {
        return elapsedMs;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getError() {
        return error;
    }

    public JsonNode getCompletion() {
        return completion;
    }

    public Instant getCompletionSentAt() {
        return completionSentAt;
    }
}
