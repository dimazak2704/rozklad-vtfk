package ua.vtc.rozklad.scheduler.generation;

import java.util.List;
import java.util.Objects;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.GenerationStopReason;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.BaseScore;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.ConstraintScore;

/**
 * How a job ended, i.e. the payload of {@code generation.completed}. Scores are {@code null}
 * when no complete timetable was reached; {@code error} is set only for FAILED; {@code stopReason}
 * only when there is a timetable; {@code baseScore} (the score of the timetable the run started from) only when there
 * is a timetable and the run did not start from scratch.
 */
record Outcome(GenerationStatus status, Long hardScore, Long softScore, List<Assignment> assignments,
               List<ConstraintScore> analysis, String error, GenerationStopReason stopReason, BaseScore baseScore) {

    Outcome {
        Objects.requireNonNull(status, "status");
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("not a terminal status: " + status);
        }
        if ((status == GenerationStatus.FAILED) != (error != null)) {
            throw new IllegalArgumentException("error must be set exactly for FAILED");
        }
        assignments = List.copyOf(assignments);
        analysis = List.copyOf(analysis);
    }

    static Outcome failed(String error) {
        return new Outcome(GenerationStatus.FAILED, null, null, List.of(), List.of(), error, null, null);
    }

    static Outcome cancelledWithoutResult() {
        return new Outcome(GenerationStatus.CANCELLED, null, null, List.of(), List.of(), null, null, null);
    }
}
