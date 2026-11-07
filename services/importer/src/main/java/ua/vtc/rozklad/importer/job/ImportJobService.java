package ua.vtc.rozklad.importer.job;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.ai.AiExtractionService;
import ua.vtc.rozklad.importer.ai.AiUnavailableException;
import ua.vtc.rozklad.importer.catalog.CatalogPort;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.catalog.CatalogUnavailableException;
import ua.vtc.rozklad.importer.config.Ids;
import ua.vtc.rozklad.importer.config.ImportProperties;
import ua.vtc.rozklad.importer.normalize.MappingDecision;
import ua.vtc.rozklad.importer.storage.FileStorage;
import ua.vtc.rozklad.importer.web.BusinessRuleException;
import ua.vtc.rozklad.importer.web.InvalidRequestException;
import ua.vtc.rozklad.importer.web.NotFoundException;
import ua.vtc.rozklad.importer.web.ProblemException;

/**
 * Synchronous side of import jobs: upload (validation, sniffing, storage), listing, admin
 * mappings (re-normalized against a fresh catalog) and the apply request. Parsing and applying run
 * later on the import executor ({@link ImportJobProcessor}).
 */
@Service
public class ImportJobService {

    private static final int MAX_MAPPINGS = 500;

    private final ImportJobRepository jobs;
    private final FileStorage storage;
    private final ImportHandlers handlers;
    private final JobNormalizer normalizer;
    private final CatalogPort catalog;
    private final AiExtractionService ai;
    private final ImportProperties properties;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ImportJobService(ImportJobRepository jobs, FileStorage storage, ImportHandlers handlers, JobNormalizer normalizer,
                            CatalogPort catalog, AiExtractionService ai, ImportProperties properties, JsonMapper jsonMapper,
                            ApplicationEventPublisher events, TransactionTemplate transaction, Clock clock) {
        this.jobs = jobs;
        this.storage = storage;
        this.handlers = handlers;
        this.normalizer = normalizer;
        this.catalog = catalog;
        this.ai = ai;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.events = events;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** One uploaded file: checks size, real type and options, stores the bytes, queues parsing. */
    public record Upload(ImportKind kind, String fileName, String contentType, byte[] content, UUID semesterId,
                         String optionsJson, String actor) {
    }

    @Transactional
    public ImportJob upload(Upload upload) {
        byte[] content = upload.content();
        if (content == null || content.length == 0) {
            throw InvalidRequestException.field("file", "Файл порожній.");
        }
        long limit = properties.maxFileSize().toBytes();
        if (content.length > limit) {
            throw ProblemException.tooLarge("Розмір файлу перевищує " + properties.maxFileSize().toMegabytes() + " МБ.");
        }
        FileType type = FileTypeSniffer.detect(content).orElseThrow(() -> ProblemException.unsupportedFile(
                "Не вдалося розпізнати тип файлу. Підтримуються XLSX, DOCX, DOC, PDF, JPEG, PNG, WEBP і GIF."));
        JobOptions options = JobOptions.from(parseOptions(upload.optionsJson()));
        boolean needsAi = handlers.get(upload.kind()).acceptUpload(type, options);
        if (needsAi) {
            try {
                ai.ensureCanCall();
            } catch (AiUnavailableException e) {
                throw new BusinessRuleException("AI_DISABLED", e.getMessage());
            }
        }
        UUID id = Ids.newId();
        String storagePath = storage.store(id, content);
        ImportJob job = ImportJob.uploaded(id, upload.kind(),
                UploadedFile.of(safeFileName(upload.fileName()), upload.contentType(), type, content), storage.kind(),
                storagePath, upload.semesterId(), options.toJson(), upload.actor(), clock.instant());
        jobs.save(job);
        events.publishEvent(new ImportJobQueued(id, ImportJobQueued.Stage.PARSE));
        return job;
    }

    @Transactional(readOnly = true)
    public Page<ImportJobRepository.ListItem> list(ImportKind kind, ImportJobStatus status, Pageable pageable) {
        return jobs.search(kind, status, pageable);
    }

    @Transactional(readOnly = true)
    public ImportJob get(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new NotFoundException("Завдання імпорту не знайдено."));
    }

    /** One admin decision as received (validated here). */
    public record MappingChange(int index, MappingDecision decision, boolean reset) {
    }

    /**
     * Merges admin decisions into the job and re-normalizes against a fresh catalog. A change without
     * {@code targetId} and {@code createNew} removes the decision for that value.
     */
    public ImportJob updateMappings(UUID id, List<MappingChange> changes) {
        ImportJob job = get(id);
        if (!job.acceptsMappings()) {
            throw ProblemException.conflict("Зіставлення можна змінювати, лише коли завдання очікує перевірки "
                    + "(NEEDS_REVIEW або READY); зараз — " + job.status() + ".");
        }
        if (changes.size() > MAX_MAPPINGS) {
            throw InvalidRequestException.field("mappings", "Не більше " + MAX_MAPPINGS + " зіставлень за раз.");
        }
        JobOptions options = JobOptions.from(job.options());
        Set<String> known = new HashSet<>();
        normalizer.entities(job.kind(), job.draft(), options).forEach(entity -> known.add(entity.key()));
        CatalogSnapshot snapshot = fetchCatalog();
        List<Map.Entry<String, String>> errors = new ArrayList<>();
        for (MappingChange change : changes) {
            MappingDecision decision = change.decision();
            String prefix = "mappings[" + change.index() + "]";
            if (!known.contains(decision.key())) {
                errors.add(Map.entry(prefix + ".raw", "Значення «" + decision.raw() + "» (" + decision.type().label()
                        + ") відсутнє в цьому імпорті."));
            }
            if (decision.mapsToExisting() && !exists(snapshot, decision)) {
                errors.add(Map.entry(prefix + ".targetId", "Обраного запису немає в каталозі."));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        Map<String, MappingDecision> merged = new LinkedHashMap<>();
        job.mappings().forEach(decision -> merged.put(decision.key(), decision));
        for (MappingChange change : changes) {
            if (change.reset()) {
                merged.remove(change.decision().key());
            } else {
                merged.put(change.decision().key(), change.decision());
            }
        }
        List<MappingDecision> decisions = List.copyOf(merged.values());
        JobNormalizer.Normalized normalized = normalizer.normalize(job.kind(), job.draft(), options, decisions, snapshot);
        long expectedVersion = job.version();
        return transaction.execute(status -> {
            ImportJob fresh = jobs.findById(id).orElseThrow(() -> new NotFoundException("Завдання імпорту не знайдено."));
            if (fresh.version() != expectedVersion) {
                throw new OptimisticLockingFailureException("Import job " + id + " changed concurrently");
            }
            fresh.remapped(decisions, normalized.rows(), clock.instant());
            return fresh;
        });
    }

    /**
     * Queues applying. Idempotent: an applied or applying job is returned unchanged.
     *
     * @param semesterId overrides the semester given at upload (required for everything but orders)
     */
    @Transactional
    public ImportJob requestApply(UUID id, UUID semesterId) {
        ImportJob job = get(id);
        switch (job.status()) {
            case APPLIED, APPLYING -> {
                return job;
            }
            case UPLOADED, PARSING -> throw ProblemException.conflict("Файл ще обробляється — зачекайте завершення розбору.");
            case NEEDS_REVIEW -> throw new BusinessRuleException("IMPORT_NEEDS_REVIEW", job.summary().conflicts() > 0
                    ? "Спершу розв'яжіть позначені розбіжності: оберіть відповідники або підтвердіть створення нових записів."
                    : "Імпорт потребує перевірки.");
            default -> {
                // READY або FAILED після невдалого застосування
            }
        }
        if (!job.canApply()) {
            throw new BusinessRuleException("IMPORT_FAILED", "Файл не вдалося розібрати — завантажте його ще раз.");
        }
        UUID effectiveSemester = semesterId != null ? semesterId : job.semesterId();
        if (job.kind() != ImportKind.ORDER_DOCUMENT && effectiveSemester == null) {
            throw new BusinessRuleException("SEMESTER_REQUIRED", "Оберіть семестр, у який застосувати імпорт.");
        }
        boolean loadKind = job.kind() == ImportKind.DISCIPLINE_PROGRAM || job.kind() == ImportKind.CURRICULUM;
        if (loadKind && JobOptions.from(job.options()).groupCodes().isEmpty()) {
            throw new BusinessRuleException("GROUPS_REQUIRED", "Під час завантаження вкажіть групи (options.groupCodes), "
                    + "яким додати навантаження.");
        }
        job.startApplying(effectiveSemester, clock.instant());
        events.publishEvent(new ImportJobQueued(id, ImportJobQueued.Stage.APPLY));
        return job;
    }

    private CatalogSnapshot fetchCatalog() {
        try {
            return catalog.snapshot();
        } catch (CatalogUnavailableException e) {
            throw ProblemException.unavailable(e.getMessage() + " Спробуйте пізніше.");
        }
    }

    private static boolean exists(CatalogSnapshot snapshot, MappingDecision decision) {
        return switch (decision.type()) {
            case TEACHER -> snapshot.teacher(decision.targetId()).isPresent();
            case DISCIPLINE -> snapshot.discipline(decision.targetId()).isPresent();
            case GROUP -> snapshot.group(decision.targetId()).isPresent();
            case ROOM -> snapshot.room(decision.targetId()).isPresent();
        };
    }

    private JsonNode parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readTree(json);
        } catch (JacksonException e) {
            throw InvalidRequestException.field("options", "Параметри мають бути коректним JSON-об'єктом.");
        }
    }

    /** Base name only (no client paths), printable, at most 255 characters. */
    static String safeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty()) {
            name = "файл";
        }
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }
}
