package ua.vtc.rozklad.importer.job;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.ai.AiBudgetExceededException;
import ua.vtc.rozklad.importer.ai.AiExtractionException;
import ua.vtc.rozklad.importer.ai.AiUnavailableException;
import ua.vtc.rozklad.importer.ai.AiUsageService;
import ua.vtc.rozklad.importer.apply.ApplyException;
import ua.vtc.rozklad.importer.apply.CoreImportException;
import ua.vtc.rozklad.importer.catalog.CatalogPort;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.catalog.CatalogUnavailableException;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.storage.FileStorage;
import ua.vtc.rozklad.importer.storage.StoredFileMissingException;

/**
 * Runs parsing and applying on the bounded import executor, after the queuing transaction
 * committed. Long work (POI, AI, core calls) happens outside transactions; state transitions are
 * short transactions. Expected failures become the job's Ukrainian {@code error}; unexpected ones
 * are logged with the stack trace and shown as an internal error.
 */
@Component
public class ImportJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImportJobProcessor.class);
    private static final String INTERNAL_ERROR = "Сталася внутрішня помилка під час обробки. Спробуйте ще раз або "
            + "повідомте адміністратора.";

    private final ImportJobRepository jobs;
    private final ImportHandlers handlers;
    private final JobNormalizer normalizer;
    private final CatalogPort catalog;
    private final FileStorage storage;
    private final AiUsageService aiUsage;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transaction;
    private final TaskExecutor executor;
    private final Clock clock;

    public ImportJobProcessor(ImportJobRepository jobs, ImportHandlers handlers, JobNormalizer normalizer, CatalogPort catalog,
                              FileStorage storage, AiUsageService aiUsage, JsonMapper jsonMapper,
                              PlatformTransactionManager transactionManager,
                              @Qualifier(ImportExecutorConfig.EXECUTOR) TaskExecutor executor, Clock clock) {
        this.jobs = jobs;
        this.handlers = handlers;
        this.normalizer = normalizer;
        this.catalog = catalog;
        this.storage = storage;
        this.aiUsage = aiUsage;
        this.jsonMapper = jsonMapper;
        // завжди нова транзакція: обробка стартує з after-commit колбеку, де приєднання до завершеної
        // транзакції мовчки втратило б зміни
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.executor = executor;
        this.clock = clock;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onQueued(ImportJobQueued event) {
        submit(event);
    }

    /** Uploaded jobs are parsed after a restart; interrupted ones are marked (see {@link ImportJob#interrupted}). */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        List<ImportJob> pending = transaction.execute(status -> jobs.findByStatusIn(EnumSet.of(ImportJobStatus.UPLOADED,
                ImportJobStatus.PARSING, ImportJobStatus.APPLYING)));
        for (ImportJob job : pending) {
            if (job.status() == ImportJobStatus.UPLOADED) {
                submit(new ImportJobQueued(job.id(), ImportJobQueued.Stage.PARSE));
            } else {
                update(job.id(), fresh -> fresh.interrupted(clock.instant()));
                log.warn("Import job {} was interrupted by a restart in status {}", job.id(), job.status());
            }
        }
    }

    void submit(ImportJobQueued event) {
        try {
            executor.execute(() -> run(event));
        } catch (TaskRejectedException e) {
            log.warn("Import queue is full, job {} ({}) rejected", event.jobId(), event.stage());
            String message = "Черга обробки переповнена. Спробуйте ще раз за кілька хвилин.";
            update(event.jobId(), job -> {
                if (event.stage() == ImportJobQueued.Stage.PARSE) {
                    job.parseFailed(message, null, clock.instant());
                } else {
                    job.applyFailed(message, clock.instant());
                }
            });
        }
    }

    private void run(ImportJobQueued event) {
        MDC.put("importJob", event.jobId().toString());
        try {
            if (event.stage() == ImportJobQueued.Stage.PARSE) {
                parse(event.jobId());
            } else {
                apply(event.jobId());
            }
        } finally {
            MDC.remove("importJob");
        }
    }

    // ───────────────────────── розбір ─────────────────────────

    void parse(UUID id) {
        ImportJob job = transaction.execute(status -> {
            ImportJob found = jobs.findById(id).orElse(null);
            if (found == null || found.status() != ImportJobStatus.UPLOADED) {
                return null;
            }
            found.startParsing(clock.instant());
            return found;
        });
        if (job == null) {
            return;
        }
        long started = System.nanoTime();
        try {
            ImportHandler handler = handlers.get(job.kind());
            JobOptions options = JobOptions.from(job.options());
            byte[] content = storage.load(id, job.storagePath());
            ImportHandler.ParseOutcome outcome = handler.parse(
                    new ImportHandler.ParseInput(id, job.fileName(), job.fileType(), content, options));
            JsonNode draft = jsonMapper.valueToTree(outcome.draft());
            List<ReportRow> entityRows = entityRows(job, draft, options);
            update(id, fresh -> fresh.parsed(draft, outcome.issues(), entityRows, aiUsage.usageOfJob(id), clock.instant()));
            log.info("Import job {} ({}) parsed in {} ms", id, job.kind(), (System.nanoTime() - started) / 1_000_000);
        } catch (ImportParseException | AiExtractionException | AiUnavailableException | AiBudgetExceededException
                 | StoredFileMissingException e) {
            log.info("Import job {} could not be parsed: {}", id, e.getMessage());
            update(id, fresh -> fresh.parseFailed(e.getMessage(), aiUsage.usageOfJob(id), clock.instant()));
        } catch (RuntimeException e) {
            log.error("Import job {} failed while parsing", id, e);
            update(id, fresh -> fresh.parseFailed(INTERNAL_ERROR, aiUsage.usageOfJob(id), clock.instant()));
        }
    }

    private List<ReportRow> entityRows(ImportJob job, JsonNode draft, JobOptions options) {
        CatalogSnapshot snapshot;
        try {
            snapshot = catalog.snapshot();
        } catch (CatalogUnavailableException e) {
            return List.of(JobNormalizer.catalogUnavailable(e.getMessage()));
        }
        return normalizer.normalize(job.kind(), draft, options, job.mappings(), snapshot).rows();
    }

    // ───────────────────────── застосування ─────────────────────────

    void apply(UUID id) {
        ImportJob job = transaction.execute(status -> jobs.findById(id).orElse(null));
        if (job == null || job.status() != ImportJobStatus.APPLYING) {
            return;
        }
        try {
            ImportHandler handler = handlers.get(job.kind());
            JobOptions options = JobOptions.from(job.options());
            CatalogSnapshot snapshot = catalog.snapshot();
            JobNormalizer.Normalized normalized = normalizer.normalize(job.kind(), job.draft(), options, job.mappings(),
                    snapshot);
            if (handler.entitiesBlockApply() && normalized.blocking()) {
                update(id, fresh -> fresh.needsReviewAgain(normalized.rows(), "Каталог у core змінився після перевірки — "
                        + "перегляньте зіставлення і застосуйте ще раз.", clock.instant()));
                return;
            }
            JobApplySteps steps = new JobApplySteps(id, job.result(),
                    result -> update(id, fresh -> fresh.checkpoint(result, clock.instant())));
            JsonNode result = handler.apply(new ImportHandler.ApplyInput(id, job.fileName(), job.draft(), options,
                    job.semesterId(), normalized.result(), snapshot, steps));
            update(id, fresh -> fresh.applied(result, clock.instant()));
            log.info("Import job {} ({}) applied", id, job.kind());
        } catch (CoreImportException e) {
            if (e.fixableByReview()) {
                log.info("Import job {} was rejected by core and returns to review: {}", id, e.getMessage());
                update(id, fresh -> fresh.rejectedByCore(e.getMessage(), clock.instant()));
            } else {
                log.info("Import job {} could not be applied: {}", id, e.getMessage());
                update(id, fresh -> fresh.applyFailed(e.getMessage(), clock.instant()));
            }
        } catch (ApplyException | CatalogUnavailableException e) {
            log.info("Import job {} could not be applied: {}", id, e.getMessage());
            update(id, fresh -> fresh.applyFailed(e.getMessage(), clock.instant()));
        } catch (RuntimeException e) {
            log.error("Import job {} failed while applying", id, e);
            update(id, fresh -> fresh.applyFailed(INTERNAL_ERROR, clock.instant()));
        }
    }

    private void update(UUID id, Consumer<ImportJob> change) {
        try {
            transaction.executeWithoutResult(status -> jobs.findById(id).ifPresent(change));
        } catch (IllegalStateException e) {
            // стан змінився паралельно (наприклад, після перезапуску) — нічого не перезаписуємо
            log.warn("Import job {} was not updated: {}", id, e.getMessage());
        }
    }
}
