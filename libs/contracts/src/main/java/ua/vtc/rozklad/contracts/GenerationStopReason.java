package ua.vtc.rozklad.contracts;

/**
 * Why a generation job stopped with a timetable ({@code generation.completed}, additive). Append values only:
 * a consumer of an older build cannot read a value it does not know.
 */
public enum GenerationStopReason {
    /** Auto mode or a plateau before the time limit: the best timetable stopped getting better. */
    NO_IMPROVEMENT,
    /** The time limit of the request ran out (requests with {@code timeLimitSeconds}, the old fixed mode). */
    TIME_LIMIT,
    /** Auto mode reached the scheduler's safety cap ({@code app.scheduler.auto.time-cap}). */
    SAFETY_CAP,
    /** The dispatcher stopped the job; the best timetable found so far is kept. */
    STOPPED
}
