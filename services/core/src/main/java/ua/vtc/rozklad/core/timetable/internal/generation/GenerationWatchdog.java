package ua.vtc.rozklad.core.timetable.internal.generation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.core.timetable.GenerationJob;
import ua.vtc.rozklad.core.timetable.GenerationJobRepository;

/**
 * Fails jobs the scheduler went silent on, so a lost message or a crashed scheduler never blocks the
 * semester (only one active job per semester is allowed). A queued job may wait its time limit plus
 * the grace period, a running one the grace period after its last progress. The scheduler is told
 * to stop, in case it is still alive.
 */
@Component
class GenerationWatchdog {

    static final String TIMEOUT_REASON = "Планувальник не відповів вчасно: задачу зупинено. Спробуйте ще раз.";

    private static final Logger log = LoggerFactory.getLogger(GenerationWatchdog.class);

    private final GenerationJobRepository jobs;
    private final GenerationResultHandler handler;
    private final GenerationProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    GenerationWatchdog(GenerationJobRepository jobs, GenerationResultHandler handler, GenerationProperties properties,
                       ApplicationEventPublisher events, TransactionTemplate transaction, Clock clock) {
        this.jobs = jobs;
        this.handler = handler;
        this.properties = properties;
        this.events = events;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.timetable.generation.watchdog-interval:PT1M}",
            initialDelayString = "${app.timetable.generation.watchdog-interval:PT1M}")
    void failStaleJobs() {
        Instant now = clock.instant();
        jobs.findAllByStatusIn(GenerationService.ACTIVE).stream()
                .filter(job -> now.isAfter(deadline(job)))
                .forEach(job -> transaction.executeWithoutResult(status -> handler
                        .failIfStale(job.getId(), locked -> now.isAfter(deadline(locked)), now, TIMEOUT_REASON)
                        .ifPresent(failed -> {
                            log.warn("Generation job {} failed by the watchdog: no news from the scheduler",
                                    failed.id());
                            events.publishEvent(new GenerationCancelRequested(EventEnvelope.create(clock),
                                    failed.id()));
                        })));
    }

    Instant deadline(GenerationJob job) {
        Duration grace = properties.timeoutGrace();
        if (job.getLastProgressAt() != null) {
            return job.getLastProgressAt().plus(grace);
        }
        int timeLimit = job.getParams().path("timeLimitSeconds").asInt(0);
        return job.getCreatedAt().plusSeconds(timeLimit).plus(grace);
    }
}
