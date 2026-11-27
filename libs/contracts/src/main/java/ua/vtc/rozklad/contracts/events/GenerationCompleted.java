package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.GenerationStopReason;
import ua.vtc.rozklad.contracts.Parity;

/**
 * {@code generation.completed}: scheduler → core (and notification). {@code status} is one of
 * SOLVED, FAILED, CANCELLED; {@code error} is set only for FAILED. Additive {@code stopReason}: why a job with a
 * timetable stopped (SOLVED, or CANCELLED with assignments: {@code STOPPED}); {@code null} for FAILED, for CANCELLED
 * without a timetable and from older schedulers. Additive {@code baseScore}: the solver's score of the timetable the
 * run started from (the base version's lessons, warm-started), under the run's own weights and settings, before
 * solving; units with no starting place count for nothing. {@code null} for a run from scratch (nothing placed at the
 * start), for completions without a timetable and from older schedulers.
 */
public record GenerationCompleted(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID jobId,
        GenerationStatus status,
        long hardScore,
        long softScore,
        List<Assignment> assignments,
        List<ConstraintScore> analysis,
        String error,
        GenerationStopReason stopReason,
        BaseScore baseScore) implements RozkladEvent {

    public GenerationCompleted {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(status, "status");
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("status must be terminal: " + status);
        }
        assignments = EventLists.copy(assignments);
        analysis = EventLists.copy(analysis);
    }

    public GenerationCompleted(EventEnvelope envelope, UUID jobId, GenerationStatus status, long hardScore,
                               long softScore, List<Assignment> assignments, List<ConstraintScore> analysis,
                               String error, GenerationStopReason stopReason, BaseScore baseScore) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), jobId, status, hardScore, softScore,
                assignments, analysis, error, stopReason, baseScore);
    }

    /** A completion before the base score: no score of the starting timetable. */
    public GenerationCompleted(UUID eventId, Instant occurredAt, int version, UUID jobId, GenerationStatus status,
                               long hardScore, long softScore, List<Assignment> assignments,
                               List<ConstraintScore> analysis, String error, GenerationStopReason stopReason) {
        this(eventId, occurredAt, version, jobId, status, hardScore, softScore, assignments, analysis, error,
                stopReason, null);
    }

    /** A completion before the base score: no score of the starting timetable. */
    public GenerationCompleted(EventEnvelope envelope, UUID jobId, GenerationStatus status, long hardScore,
                               long softScore, List<Assignment> assignments, List<ConstraintScore> analysis,
                               String error, GenerationStopReason stopReason) {
        this(envelope, jobId, status, hardScore, softScore, assignments, analysis, error, stopReason, null);
    }

    /** The record's first schema, kept for callers: no stop reason. */
    public GenerationCompleted(UUID eventId, Instant occurredAt, int version, UUID jobId, GenerationStatus status,
                               long hardScore, long softScore, List<Assignment> assignments,
                               List<ConstraintScore> analysis, String error) {
        this(eventId, occurredAt, version, jobId, status, hardScore, softScore, assignments, analysis, error, null,
                null);
    }

    /** A completion of the first schema: no stop reason. */
    public GenerationCompleted(EventEnvelope envelope, UUID jobId, GenerationStatus status, long hardScore,
                               long softScore, List<Assignment> assignments, List<ConstraintScore> analysis,
                               String error) {
        this(envelope, jobId, status, hardScore, softScore, assignments, analysis, error, null, null);
    }

    /**
     * The solver's score of the starting timetable ({@code hardScore} ≤ 0 counts hard violations, weighted like the
     * run's final score), e.g. {@code -2} hard for two group-days over the Friday limit.
     */
    public record BaseScore(long hardScore, long softScore) {
    }

    /** Placement of a {@link ProblemSnapshot.Unit}; {@code roomId} may be {@code null}. */
    public record Assignment(String unitId, int weekday, int pair, Parity parity, UUID roomId) {

        public Assignment {
            Objects.requireNonNull(unitId, "unitId");
            Objects.requireNonNull(parity, "parity");
        }
    }

    /**
     * Score analysis row; {@code score} is the solver's formatted score, e.g. {@code 0hard/-35soft}.
     * Additive: {@code constraintId} is the constraint's ASCII id (Latin letters and spaces);
     * {@code bonus} marks a reward row (absent reads as {@code false}); {@code scopeMatchCount} counts the
     * matches that involve at least one free (non-pinned) unit, {@code null} when unknown (older jobs), in
     * which case clients use {@code matchCount}.
     */
    public record ConstraintScore(String constraint, int matchCount, String score, String constraintId,
                                  Boolean bonus, Integer scopeMatchCount) {

        public ConstraintScore {
            bonus = Boolean.TRUE.equals(bonus);
        }

        /** A row of the first schema: no id, not a bonus, scope matches unknown. */
        public ConstraintScore(String constraint, int matchCount, String score) {
            this(constraint, matchCount, score, null, false, null);
        }
    }
}
