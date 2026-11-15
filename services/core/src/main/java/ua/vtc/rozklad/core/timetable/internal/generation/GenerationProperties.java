package ua.vtc.rozklad.core.timetable.internal.generation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Generation settings ({@code app.timetable.generation.*}).
 *
 * @param timeoutGrace how long a job may stay without news from the scheduler (beyond its time
 *                     limit while queued) before the watchdog fails it; 10 minutes by default. It must be
 *                     longer than the scheduler's longest wait without a better timetable
 *                     ({@code app.scheduler.auto.stuck-limit}), when no progress is sent
 * @param autoTimeCap  how long a job without a time limit (auto mode) may run: the scheduler's safety cap
 *                     ({@code app.scheduler.auto.time-cap}); a queued auto job may wait this plus the grace;
 *                     30 minutes by default
 * @param stopGrace    how long a stopped job may wait for the scheduler's best timetable before the watchdog
 *                     fails it; 2 minutes by default
 */
@ConfigurationProperties("app.timetable.generation")
public record GenerationProperties(Duration timeoutGrace, Duration autoTimeCap, Duration stopGrace) {

    public GenerationProperties {
        if (timeoutGrace == null) {
            timeoutGrace = Duration.ofMinutes(10);
        }
        if (autoTimeCap == null) {
            autoTimeCap = Duration.ofMinutes(30);
        }
        if (stopGrace == null) {
            stopGrace = Duration.ofMinutes(2);
        }
    }
}
