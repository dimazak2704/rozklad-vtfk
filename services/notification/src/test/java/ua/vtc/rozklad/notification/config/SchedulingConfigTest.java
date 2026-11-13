package ua.vtc.rozklad.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/** Cron jobs (the morning digest) follow the stand time; with real time the scheduler is untouched. */
class SchedulingConfigTest {

    private static final Instant REAL = Instant.parse("2026-11-14T10:00:00Z");

    private final SchedulingConfig config = new SchedulingConfig();

    @Test
    void underTheStandTimeTheSchedulerRunsOnTheShiftedClock() {
        Clock stand = StandClock.create(Clock.fixed(REAL, InfrastructureConfig.KYIV), "2026-11-20T07:29:59", null,
                false);
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();

        config.standTimeSimpleScheduler(stand).customize(scheduler);

        assertThat(scheduler.getClock()).isSameAs(stand);
        // the digest cron (07:30 on weekdays, Kyiv) is one second away on the stand day
        ScheduledFuture<?> digest = scheduler.schedule(() -> { },
                new CronTrigger("0 30 7 * * MON-FRI", InfrastructureConfig.KYIV));
        try {
            long delayMillis = digest.getDelay(TimeUnit.MILLISECONDS);
            assertThat(delayMillis).isBetween(0L, 1_000L);
        } finally {
            digest.cancel(false);
            scheduler.close();
        }
        assertThat(LocalDateTime.now(stand)).isEqualTo(LocalDateTime.of(2026, 11, 20, 7, 29, 59));
    }

    @Test
    void withRealTimeTheSchedulersKeepTheirOwnClock() {
        Clock real = Clock.system(InfrastructureConfig.KYIV);
        SimpleAsyncTaskScheduler simple = new SimpleAsyncTaskScheduler();
        ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
        Clock simpleDefault = simple.getClock();
        Clock poolDefault = pool.getClock();

        config.standTimeSimpleScheduler(real).customize(simple);
        config.standTimeThreadPoolScheduler(real).customize(pool);

        assertThat(simple.getClock()).isSameAs(simpleDefault);
        assertThat(pool.getClock()).isSameAs(poolDefault);
    }

    @Test
    void thePlatformThreadSchedulerFollowsTheStandTimeToo() {
        Clock stand = StandClock.create(Clock.fixed(REAL, InfrastructureConfig.KYIV), "2026-11-20T10:00", null, false);
        ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();

        config.standTimeThreadPoolScheduler(stand).customize(pool);

        assertThat(pool.getClock()).isSameAs(stand);
    }
}
