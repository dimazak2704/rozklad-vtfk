package ua.vtc.rozklad.scheduler.generation;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.scheduler.config.SchedulerProperties;
import ua.vtc.rozklad.scheduler.messaging.ProcessedEvents;
import ua.vtc.rozklad.scheduler.solver.InvalidProblemException;
import ua.vtc.rozklad.scheduler.solver.TimetableProblemFactory;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Handles the requests from core. Idempotent: a redelivered event (same {@code eventId}) and a
 * second request for a known {@code jobId} change nothing. The solver is started and stopped only
 * after the job row is committed.
 */
@Service
public class GenerationIntake {

    static final String TIME_LIMIT_TOO_LONG = "Ліміт часу %d с перевищує дозволений максимум %d с.";

    private static final Logger log = LoggerFactory.getLogger(GenerationIntake.class);

    private final GenerationJobRepository repository;
    private final ProcessedEvents processedEvents;
    private final GenerationSolver solver;
    private final GenerationLifecycle lifecycle;
    private final TimetableProblemFactory problemFactory;
    private final SchedulerProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public GenerationIntake(GenerationJobRepository repository, ProcessedEvents processedEvents,
                            GenerationSolver solver, GenerationLifecycle lifecycle, SchedulerProperties properties,
                            JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.processedEvents = processedEvents;
        this.solver = solver;
        this.lifecycle = lifecycle;
        this.problemFactory = new TimetableProblemFactory(properties.disciplineRepeatWeight());
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional
    public void accept(GenerationRequested request) {
        UUID jobId = request.jobId();
        if (!processedEvents.markProcessed(request.eventId())) {
            log.info("Duplicate generation.requested {} for job {} ignored", request.eventId(), jobId);
            return;
        }
        if (repository.existsById(jobId)) {
            log.info("Job {} is already known, generation.requested {} ignored", jobId, request.eventId());
            return;
        }
        repository.save(GenerationJob.requested(jobId, request.semesterId(), request.timeLimitSeconds(),
                request.problem().units().size(), jsonMapper.valueToTree(request.weights()), clock.instant()));
        long maxSeconds = properties.maxTimeLimit().toSeconds();
        if (request.timeLimitSeconds() > maxSeconds) {
            String error = TIME_LIMIT_TOO_LONG.formatted(request.timeLimitSeconds(), maxSeconds);
            afterCommit(() -> lifecycle.fail(jobId, error));
            return;
        }
        Timetable problem;
        try {
            problem = problemFactory.create(request.problem(), request.weights());
        } catch (InvalidProblemException e) {
            log.warn("Job {} rejected: {}", jobId, e.getErrors());
            afterCommit(() -> lifecycle.fail(jobId, e.getMessage()));
            return;
        } catch (RuntimeException e) {
            // помилка в коді, а не в даних: задача завершується явно, а не зникає в DLQ
            log.error("Job {}: the problem could not be built", jobId, e);
            afterCommit(() -> lifecycle.fail(jobId, GenerationLifecycle.SOLVER_ERROR));
            return;
        }
        log.info("Job {} queued: {} units, {} s", jobId, problem.getUnits().size(), request.timeLimitSeconds());
        Duration limit = Duration.ofSeconds(request.timeLimitSeconds());
        afterCommit(() -> solver.start(jobId, problem, JobTermination.forLimit(limit, properties)));
    }

    @Transactional
    public void cancel(GenerationCancelRequested cancel) {
        UUID jobId = cancel.jobId();
        if (!processedEvents.markProcessed(cancel.eventId())) {
            log.info("Duplicate generation.cancel {} for job {} ignored", cancel.eventId(), jobId);
            return;
        }
        Optional<GenerationJob> found = repository.findForUpdate(jobId);
        if (found.isEmpty()) {
            // запит міг ще не дійти: «надгробок» не дасть його запустити, а core отримає CANCELLED
            log.info("Cancel of unknown job {}: recorded as cancelled", jobId);
            repository.save(GenerationJob.unknown(jobId, clock.instant()));
            afterCommit(() -> lifecycle.cancelWithoutResult(jobId));
            return;
        }
        GenerationJob job = found.get();
        if (!job.isActive()) {
            log.info("Cancel of job {} ignored: it is already {}", jobId, job.getStatus());
            return;
        }
        job.requestCancel();
        boolean queued = job.getStatus() == GenerationStatus.QUEUED;
        afterCommit(() -> {
            solver.terminate(jobId);
            if (queued) {
                lifecycle.cancelWithoutResult(jobId);
            }
        });
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    log.error("Post-commit action failed", e);
                }
            }
        });
    }
}
