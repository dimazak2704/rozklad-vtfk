package ua.vtc.rozklad.core.timetable.internal.generation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Generation settings ({@code app.timetable.generation.*}).
 *
 * @param timeoutGrace how long a job may stay without news from the scheduler (beyond its time
 *                     limit while queued) before the watchdog fails it; 10 minutes by default
 */
@ConfigurationProperties("app.timetable.generation")
public record GenerationProperties(Duration timeoutGrace) {

    public GenerationProperties {
        if (timeoutGrace == null) {
            timeoutGrace = Duration.ofMinutes(10);
        }
    }
}
