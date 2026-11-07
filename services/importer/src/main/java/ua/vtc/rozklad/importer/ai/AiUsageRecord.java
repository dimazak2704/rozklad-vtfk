package ua.vtc.rozklad.importer.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** One model call: tokens, estimated cost and outcome; {@code usageDate} is the Kyiv calendar day. */
@Entity
@Table(name = "ai_usage")
public class AiUsageRecord implements Persistable<UUID> {

    public enum Outcome {
        SUCCESS, REFUSED, TRUNCATED, INVALID, FAILED
    }

    @Id
    private UUID id;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private LocalDate usageDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiPurpose purpose;

    @Column(nullable = false)
    private String model;

    private long inputTokens;

    private long outputTokens;

    @Column(nullable = false)
    private BigDecimal costUsd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    private UUID jobId;

    protected AiUsageRecord() {
    }

    public AiUsageRecord(UUID id, Instant occurredAt, LocalDate usageDate, AiPurpose purpose, String model,
                         long inputTokens, long outputTokens, BigDecimal costUsd, Outcome outcome, UUID jobId) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.usageDate = usageDate;
        this.purpose = purpose;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
        this.outcome = outcome;
        this.jobId = jobId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** Usage rows are append-only. */
    @Override
    public boolean isNew() {
        return true;
    }

    public AiPurpose purpose() {
        return purpose;
    }

    public String model() {
        return model;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public BigDecimal costUsd() {
        return costUsd;
    }

    public Outcome outcome() {
        return outcome;
    }

    public UUID jobId() {
        return jobId;
    }
}
