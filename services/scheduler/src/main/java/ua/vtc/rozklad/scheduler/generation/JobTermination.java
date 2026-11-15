package ua.vtc.rozklad.scheduler.generation;

import ai.timefold.solver.core.config.solver.termination.TerminationCompositionStyle;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import java.util.List;
import ua.vtc.rozklad.scheduler.config.SchedulerProperties;

/**
 * When a job stops.
 * <ul>
 *   <li><b>Auto mode</b> (no time limit in the request): when the timetable has no hard violations and the best
 *   score improved by less than {@code app.scheduler.auto.min-improvement} soft points over the last
 *   {@code window} (Timefold's unimproved time with a score difference threshold); when nothing at all has improved
 *   for the longer {@code stuck-limit} (hard violations that cannot be removed, e.g. between pinned lessons); and in
 *   any case at the safety cap {@code time-cap}. While hard violations go away the job keeps going: a hard
 *   improvement is always above the threshold.</li>
 *   <li><b>Fixed limit</b> (older clients): at the requested limit, or earlier when the best score has not improved
 *   for {@code max(minUnimprovedLimit, timeLimit × unimprovedRatio)}.</li>
 * </ul>
 * The dispatcher may stop either earlier; the best timetable so far is kept. The windows were chosen on replays of
 * the college's real requests (docs/modules/scheduler.md §11 «Auto mode»).
 */
public final class JobTermination {

    private JobTermination() {
    }

    /** Auto mode: settled (0 hard, slow gains) or stuck, at the latest at the safety cap. */
    public static TerminationConfig auto(SchedulerProperties properties) {
        SchedulerProperties.Auto auto = properties.auto();
        TerminationConfig settled = new TerminationConfig()
                .withTerminationCompositionStyle(TerminationCompositionStyle.AND)
                .withBestScoreFeasible(true)
                .withUnimprovedSpentLimit(auto.window())
                .withUnimprovedScoreDifferenceThreshold("0hard/" + auto.minImprovement() + "soft");
        return new TerminationConfig()
                .withTerminationCompositionStyle(TerminationCompositionStyle.OR)
                .withSpentLimit(auto.timeCap())
                .withUnimprovedSpentLimit(auto.stuckLimit())
                .withTerminationConfigList(List.of(settled));
    }

    public static TerminationConfig forLimit(Duration timeLimit, SchedulerProperties properties) {
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
