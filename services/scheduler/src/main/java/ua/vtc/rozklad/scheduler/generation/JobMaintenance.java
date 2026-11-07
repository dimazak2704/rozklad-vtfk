package ua.vtc.rozklad.scheduler.generation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.scheduler.config.SchedulerProperties;
import ua.vtc.rozklad.scheduler.messaging.ProcessedEventRepository;

/**
 * Background care of the job table: jobs interrupted by a restart, completions the broker has not
 * confirmed, and old rows. Assumes a single scheduler instance (one solver per job id).
 */
@Component
public class JobMaintenance {

    private static final Logger log = LoggerFactory.getLogger(JobMaintenance.class);

    private final GenerationJobRepository repository;
    private final ProcessedEventRepository processedEvents;
    private final GenerationLifecycle lifecycle;
    private final SchedulerProperties properties;
    private final Clock clock;
    // задачі, отримані до старту цього процесу, вже ніхто не розв'язує
    private final Instant processStartedAt;

    public JobMaintenance(GenerationJobRepository repository, ProcessedEventRepository processedEvents,
                          GenerationLifecycle lifecycle, SchedulerProperties properties, Clock clock) {
        this.repository = repository;
        this.processedEvents = processedEvents;
        this.lifecycle = lifecycle;
        this.properties = properties;
        this.clock = clock;
        this.processStartedAt = clock.instant();
    }

    /** Jobs that were active when the previous process stopped fail with an explanation. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobs() {
        List<UUID> interrupted = repository.findByStatusIn(List.of(GenerationStatus.QUEUED, GenerationStatus.RUNNING))
                .stream()
                .filter(job -> job.getReceivedAt().isBefore(processStartedAt))
                .map(GenerationJob::getId)
                .toList();
        if (!interrupted.isEmpty()) {
            log.warn("Jobs interrupted by a restart: {}", interrupted);
        }
        interrupted.forEach(jobId -> lifecycle.fail(jobId, GenerationLifecycle.INTERRUPTED));
        resendPendingCompletions();
    }

    /** Completions the broker did not confirm (it was down) are sent again with the same event id. */
    @Scheduled(fixedDelayString = "${app.scheduler.completion-resend-interval}",
            initialDelayString = "${app.scheduler.completion-resend-interval}")
    public void resendPendingCompletions() {
        // свіжі завершення ще доставляє потік, що їх створив
        Instant finishedBefore = clock.instant().minus(properties.completionResendInterval());
        for (UUID jobId : repository.findUnsentCompletions(finishedBefore)) {
            log.info("Resending generation.completed of job {}", jobId);
            lifecycle.redeliver(jobId);
        }
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Europe/Kyiv")
    @Transactional
    public void purgeOld() {
        Instant before = clock.instant().minus(properties.retention());
        int jobs = repository.deleteFinishedBefore(before);
        int events = processedEvents.deleteProcessedBefore(before);
        if (jobs + events > 0) {
            log.info("Removed {} finished jobs and {} processed event ids older than {}", jobs, events,
                    properties.retention());
        }
    }
}
