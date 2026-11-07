package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.Parity;

/**
 * {@code generation.completed}: scheduler → core (and notification). {@code status} is one of
 * SOLVED, FAILED, CANCELLED; {@code error} is set only for FAILED.
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
        String error) implements RozkladEvent {

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
                               String error) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), jobId, status, hardScore, softScore,
                assignments, analysis, error);
    }

    /** Placement of a {@link ProblemSnapshot.Unit}; {@code roomId} may be {@code null}. */
    public record Assignment(String unitId, int weekday, int pair, Parity parity, UUID roomId) {

        public Assignment {
            Objects.requireNonNull(unitId, "unitId");
            Objects.requireNonNull(parity, "parity");
        }
    }

    /** Score analysis row; {@code score} is the solver's formatted score, e.g. {@code 0hard/-35soft}. */
    public record ConstraintScore(String constraint, int matchCount, String score) {
    }
}
