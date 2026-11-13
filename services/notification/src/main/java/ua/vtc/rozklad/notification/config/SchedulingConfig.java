package ua.vtc.rozklad.notification.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} jobs (morning digest, confirmation deadlines, housekeeping); switch
 * off with {@code app.scheduling.enabled=false} (integration tests run the jobs explicitly).
 * Under the stand time the scheduler runs on the shifted clock, so the morning digest comes at
 * 07:30 of the stand day; with real time the scheduler keeps its own clock.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    /** Virtual threads (the default here). */
    @Bean
    SimpleAsyncTaskSchedulerCustomizer standTimeSimpleScheduler(Clock clock) {
        return scheduler -> {
            if (StandClock.isShifted(clock)) {
                scheduler.setClock(clock);
            }
        };
    }

    /** Platform threads ({@code spring.threads.virtual.enabled=false}). */
    @Bean
    ThreadPoolTaskSchedulerCustomizer standTimeThreadPoolScheduler(Clock clock) {
        return scheduler -> {
            if (StandClock.isShifted(clock)) {
                scheduler.setClock(clock);
            }
        };
    }
}
