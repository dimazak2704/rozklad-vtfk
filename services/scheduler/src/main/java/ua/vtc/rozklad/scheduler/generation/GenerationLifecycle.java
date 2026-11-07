package ua.vtc.rozklad.scheduler.generation;

import ai.timefold.solver.core.api.score.HardSoftScore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.scheduler.messaging.EventPublisher;
import ua.vtc.rozklad.scheduler.solver.TimetableResults;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * State changes of generation jobs and the events that announce them. A job ends exactly once:
 * the terminal status and its {@code generation.completed} payload are stored in one transaction
 * under a row lock, then the event is sent with a broker confirmation and marked as delivered.
 * Undelivered completions are retried by {@link JobMaintenance}.
 */
@Service
public class GenerationLifecycle {

    static final String NOT_PLACED = "За відведений час вдалося розставити лише %d з %d занять. "
            + "Збільште ліміт часу генерації.";
    static final String SOLVER_ERROR = "Генерацію зупинено через внутрішню помилку планувальника. "
            + "Спробуйте ще раз або повідомте адміністратора.";
    static final String INTERRUPTED = "Генерацію перервано: сервіс планувальника перезапускався. "
            + "Запустіть генерацію ще раз.";

    private static final Logger log = LoggerFactory.getLogger(GenerationLifecycle.class);

    private final GenerationJobRepository repository;
    private final EventPublisher publisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public GenerationLifecycle(GenerationJobRepository repository, EventPublisher publisher, JsonMapper jsonMapper,
                               Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        // викликається з потоків розв'язувача та з afterCommit уже завершених транзакцій — завжди власна транзакція
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The solver picked the job up. */
    public void markRunning(UUID jobId) {
        transactions.executeWithoutResult(status -> repository.findForUpdate(jobId)
                .ifPresent(job -> job.start(clock.instant())));
    }

    /** Stores the best score of a running job and announces it after the commit. */
    public void recordProgress(UUID jobId, long elapsedMs, HardSoftScore score) {
        transactions.executeWithoutResult(status -> {
            if (repository.updateProgress(jobId, score.hardScore(), score.softScore(), elapsedMs) == 1) {
                publisher.publishAfterCommit(new GenerationProgress(EventEnvelope.create(clock), jobId, elapsedMs,
                        score.hardScore(), score.softScore()));
            }
        });
    }

    /**
     * The solver stopped (time limit, plateau, cancel or shutdown) with its best timetable. A
     * cancelled job keeps the best timetable found so far, so the dispatcher may still apply it.
     */
    public void finishWithSolution(UUID jobId, Timetable solution, boolean interruptedByShutdown) {
        if (interruptedByShutdown) {
            complete(jobId, job -> Outcome.failed(INTERRUPTED));
            return;
        }
        if (!solution.isFullyPlaced()) {
            long placed = solution.getUnits().stream().filter(LessonUnit::isPlaced).count();
            String notPlaced = NOT_PLACED.formatted(placed, solution.getUnits().size());
            complete(jobId, job -> job.isCancelRequested() ? Outcome.cancelledWithoutResult() : Outcome.failed(notPlaced));
            return;
        }
        HardSoftScore score = solution.getScore();
        List<GenerationCompleted.Assignment> assignments = TimetableResults.assignments(solution);
        List<GenerationCompleted.ConstraintScore> analysis = TimetableResults.analysis(solution);
        complete(jobId, job -> new Outcome(job.isCancelRequested() ? GenerationStatus.CANCELLED : GenerationStatus.SOLVED,
                score.hardScore(), score.softScore(), assignments, analysis, null));
    }

    public void fail(UUID jobId, String error) {
        complete(jobId, job -> Outcome.failed(error));
    }

    public void cancelWithoutResult(UUID jobId) {
        complete(jobId, job -> Outcome.cancelledWithoutResult());
    }

    /**
     * Ends the job if it is still active; a job that has already ended is left as it is (the
     * solver, a cancel and a restart may race to finish the same job).
     */
    void complete(UUID jobId, Function<GenerationJob, Outcome> decide) {
        GenerationCompleted event = transactions.execute(status -> {
            GenerationJob job = repository.findForUpdate(jobId).orElse(null);
            if (job == null || !job.isActive()) {
                log.debug("Job {} is unknown or already finished, outcome dropped", jobId);
                return null;
            }
            Outcome outcome = decide.apply(job);
            GenerationCompleted completed = new GenerationCompleted(EventEnvelope.create(clock), jobId,
                    outcome.status(), orZero(outcome.hardScore()), orZero(outcome.softScore()), outcome.assignments(),
                    outcome.analysis(), outcome.error());
            job.finish(outcome.status(), outcome.hardScore(), outcome.softScore(), outcome.error(),
                    jsonMapper.valueToTree(completed), clock.instant());
            return completed;
        });
        if (event != null) {
            log.info("Job {} finished: {} {}/{}", jobId, event.status(), event.hardScore(), event.softScore());
            deliver(jobId, event);
        }
    }

    /** Sends a stored completion again; used for completions the broker has not confirmed yet. */
    void redeliver(UUID jobId) {
        GenerationCompleted event = transactions.execute(status -> repository.findById(jobId)
                .filter(job -> job.getCompletion() != null && job.getCompletionSentAt() == null)
                .map(job -> jsonMapper.treeToValue(job.getCompletion(), GenerationCompleted.class))
                .orElse(null));
        if (event != null) {
            deliver(jobId, event);
        }
    }

    private void deliver(UUID jobId, GenerationCompleted event) {
        if (publisher.publishConfirmed(event)) {
            transactions.executeWithoutResult(status -> repository.findForUpdate(jobId)
                    .ifPresent(job -> job.markCompletionSent(clock.instant())));
        }
    }

    private static long orZero(Long score) {
        return score == null ? 0 : score;
    }
}
