package ua.vtc.rozklad.scheduler.generation;

import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import ua.vtc.rozklad.scheduler.config.SchedulerProperties;

/**
 * When a job stops: at its time limit, or earlier when the best score has not improved for
 * {@code max(minUnimprovedLimit, timeLimit × unimprovedRatio)} — a plateau rarely ends with a
 * better timetable, and the dispatcher gets the result sooner.
 */
final class JobTermination {

    private JobTermination() {
    }

    static TerminationConfig forLimit(Duration timeLimit, SchedulerProperties properties) {
        TerminationConfig termination = new TerminationConfig().withSpentLimit(timeLimit);
        Duration unimproved = unimprovedLimit(timeLimit, properties);
        if (unimproved.compareTo(timeLimit) < 0) {
            termination.withUnimprovedSpentLimit(unimproved);
        }
        return termination;
    }

    static Duration unimprovedLimit(Duration timeLimit, SchedulerProperties properties) {
        Duration share = Duration.ofMillis(Math.round(timeLimit.toMillis() * properties.unimprovedRatio()));
        return share.compareTo(properties.minUnimprovedLimit()) > 0 ? share : properties.minUnimprovedLimit();
    }
}
