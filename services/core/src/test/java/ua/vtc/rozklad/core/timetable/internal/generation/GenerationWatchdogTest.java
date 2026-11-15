package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import ua.vtc.rozklad.core.timetable.GenerationJob;
import ua.vtc.rozklad.core.timetable.GenerationJobRepository;

/**
 * When the watchdog gives up on a job: a fixed-limit job after its limit plus the grace, an auto-mode job (no limit)
 * after the scheduler's safety cap plus the grace, a running job the grace after its last progress, and a stopped job
 * the stop grace after the stop.
 */
class GenerationWatchdogTest {

    private static final Instant CREATED = Instant.parse("2026-11-15T08:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GenerationProperties properties = new GenerationProperties(Duration.ofMinutes(10),
            Duration.ofMinutes(30), Duration.ofMinutes(2));
    private final GenerationWatchdog watchdog = new GenerationWatchdog(mock(GenerationJobRepository.class),
            mock(GenerationResultHandler.class), properties, mock(ApplicationEventPublisher.class),
            mock(TransactionTemplate.class), Clock.fixed(CREATED, ZoneOffset.UTC));

    @Test
    void queuedJobWithALimitWaitsItsLimitPlusTheGrace() {
        GenerationJob job = job(300);

        assertThat(job.isAutoMode()).isFalse();
        assertThat(watchdog.deadline(job)).isEqualTo(CREATED.plusSeconds(300).plus(Duration.ofMinutes(10)));
    }

    @Test
    void queuedAutoJobWaitsTheSafetyCapPlusTheGrace() {
        GenerationJob job = job(null);

        assertThat(job.isAutoMode()).isTrue();
        assertThat(watchdog.deadline(job)).isEqualTo(CREATED.plus(Duration.ofMinutes(40)));
    }

    @Test
    void runningAutoJobIsAliveWhileProgressComes() {
        GenerationJob job = job(null);
        Instant progress = CREATED.plus(Duration.ofMinutes(25));
        job.recordProgress(progress, JSON.createArrayNode(), "0hard/-10soft");

        assertThat(watchdog.deadline(job)).as("25 min into a healthy run, not at the 10 min grace from the start")
                .isEqualTo(progress.plus(Duration.ofMinutes(10)));
        assertThat(GenerationWatchdog.reason(job)).isEqualTo(GenerationWatchdog.TIMEOUT_REASON);
    }

    @Test
    void stoppedJobWaitsTheStopGraceForItsBestTimetable() {
        GenerationJob job = job(null);
        job.recordProgress(CREATED.plusSeconds(10), JSON.createArrayNode(), "0hard/-10soft");
        Instant stop = CREATED.plusSeconds(90);
        job.requestStop(stop);
        job.recordProgress(CREATED.plusSeconds(95), JSON.createArrayNode(), "0hard/-9soft");

        assertThat(watchdog.deadline(job)).isEqualTo(stop.plus(Duration.ofMinutes(2)));
        assertThat(GenerationWatchdog.reason(job)).isEqualTo(GenerationWatchdog.STOP_TIMEOUT_REASON);
    }

    private static GenerationJob job(Integer timeLimitSeconds) {
        ObjectNode params = JSON.createObjectNode();
        if (timeLimitSeconds != null) {
            params.put("timeLimitSeconds", timeLimitSeconds);
        }
        GenerationJob job = new GenerationJob(UUID.randomUUID(), params, "admin");
        ReflectionTestUtils.setField(job, "createdAt", CREATED);
        return job;
    }
}
