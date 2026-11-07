package ua.vtc.rozklad.contracts;

/**
 * Status of a timetable generation job. {@code generation.completed} carries only the terminal
 * values {@link #SOLVED}, {@link #FAILED} and {@link #CANCELLED}.
 */
public enum GenerationStatus {
    QUEUED,
    RUNNING,
    SOLVED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SOLVED || this == FAILED || this == CANCELLED;
    }
}
