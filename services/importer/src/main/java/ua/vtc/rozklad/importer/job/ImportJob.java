package ua.vtc.rozklad.importer.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.importer.ai.AiJobUsage;
import ua.vtc.rozklad.importer.config.ImportProperties;
import ua.vtc.rozklad.importer.normalize.MappingDecision;
import ua.vtc.rozklad.importer.report.ImportSummary;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.Severity;

/**
 * An import job: the uploaded file's metadata, the parsed draft, the report and the admin's
 * mappings. State transitions are methods; illegal transitions throw {@link IllegalStateException}
 * (services check the status first and answer with 409/422).
 */
@Entity
@Table(name = "import_job")
public class ImportJob implements Persistable<UUID> {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportJobStatus status;

    @Column(nullable = false)
    private String fileName;

    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileType fileType;

    private long fileSize;

    @Column(nullable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportProperties.Storage storage;

    private String storagePath;

    private UUID semesterId;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode options;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode draft;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ReportRow> issues;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ReportRow> report;

    @JdbcTypeCode(SqlTypes.JSON)
    private ImportSummary summary;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<MappingDecision> mappings;

    @JdbcTypeCode(SqlTypes.JSON)
    private AiJobUsage ai;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode result;

    private String error;

    @Column(nullable = false)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant parsedAt;

    private Instant appliedAt;

    @Version
    private long version;

    @Transient
    private boolean persisted;

    protected ImportJob() {
    }

    /** A freshly uploaded file waiting for parsing. */
    public static ImportJob uploaded(UUID id, ImportKind kind, UploadedFile file, ImportProperties.Storage storage,
                                     String storagePath, UUID semesterId, JsonNode options, String createdBy, Instant now) {
        ImportJob job = new ImportJob();
        job.id = id;
        job.kind = kind;
        job.status = ImportJobStatus.UPLOADED;
        job.fileName = file.fileName();
        job.contentType = file.contentType();
        job.fileType = file.type();
        job.fileSize = file.size();
        job.sha256 = file.sha256();
        job.storage = storage;
        job.storagePath = storagePath;
        job.semesterId = semesterId;
        job.options = options;
        job.mappings = List.of();
        job.issues = List.of();
        job.report = List.of();
        job.summary = new ImportSummary(0, 0, 0, 0);
        job.createdBy = createdBy;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public void startParsing(Instant now) {
        require(status == ImportJobStatus.UPLOADED, "parse");
        status = ImportJobStatus.PARSING;
        error = null;
        updatedAt = now;
    }

    /** Parsing finished: stores the draft and the report; status follows the report. */
    public void parsed(JsonNode draft, List<ReportRow> parseIssues, List<ReportRow> entityRows, AiJobUsage ai, Instant now) {
        require(status == ImportJobStatus.PARSING, "finish parsing");
        this.draft = draft;
        this.issues = List.copyOf(parseIssues);
        this.ai = ai;
        this.parsedAt = now;
        updateReport(entityRows);
        status = summary.blocking() ? ImportJobStatus.NEEDS_REVIEW : ImportJobStatus.READY;
        updatedAt = now;
    }

    public void parseFailed(String message, AiJobUsage ai, Instant now) {
        require(status == ImportJobStatus.PARSING || status == ImportJobStatus.UPLOADED, "fail parsing");
        status = ImportJobStatus.FAILED;
        error = message;
        this.ai = ai;
        updatedAt = now;
    }

    /** New admin mappings and the matching rows computed with them. */
    public void remapped(List<MappingDecision> mappings, List<ReportRow> entityRows, Instant now) {
        require(acceptsMappings(), "change mappings");
        this.mappings = List.copyOf(mappings);
        updateReport(entityRows);
        status = summary.blocking() ? ImportJobStatus.NEEDS_REVIEW : ImportJobStatus.READY;
        if (status == ImportJobStatus.READY) {
            error = null;
        }
        updatedAt = now;
    }

    /** The admin replaced the draft with a corrected one: new parse remarks and matching rows. */
    public void revised(JsonNode draft, List<ReportRow> parseIssues, List<ReportRow> entityRows, Instant now) {
        require(acceptsMappings(), "revise the draft");
        this.draft = draft;
        this.issues = List.copyOf(parseIssues);
        updateReport(entityRows);
        status = summary.blocking() ? ImportJobStatus.NEEDS_REVIEW : ImportJobStatus.READY;
        if (status == ImportJobStatus.READY) {
            error = null;
        }
        updatedAt = now;
    }

    public void startApplying(UUID semesterId, Instant now) {
        require(canApply(), "apply");
        if (semesterId != null) {
            this.semesterId = semesterId;
        }
        status = ImportJobStatus.APPLYING;
        error = null;
        updatedAt = now;
    }

    /** A step of applying succeeded; its result is kept so a retry skips it. */
    public void checkpoint(JsonNode result, Instant now) {
        require(status == ImportJobStatus.APPLYING, "store an apply step");
        this.result = result;
        updatedAt = now;
    }

    public void applied(JsonNode result, Instant now) {
        require(status == ImportJobStatus.APPLYING, "finish applying");
        this.result = result;
        status = ImportJobStatus.APPLIED;
        appliedAt = now;
        updatedAt = now;
    }

    public void applyFailed(String message, Instant now) {
        require(status == ImportJobStatus.APPLYING, "fail applying");
        status = ImportJobStatus.FAILED;
        error = message;
        updatedAt = now;
    }

    /**
     * Core rejected the content (e.g. the number is taken or the date is wrong): back to review with
     * core's answer as the error, so the admin corrects the draft or the mappings instead of paying
     * for another AI reading.
     */
    public void rejectedByCore(String message, Instant now) {
        require(status == ImportJobStatus.APPLYING, "return to review");
        status = ImportJobStatus.NEEDS_REVIEW;
        error = message;
        updatedAt = now;
    }

    /** The catalog changed after review: back to review with fresh matching rows. */
    public void needsReviewAgain(List<ReportRow> entityRows, String message, Instant now) {
        require(status == ImportJobStatus.APPLYING, "return to review");
        updateReport(entityRows);
        status = ImportJobStatus.NEEDS_REVIEW;
        error = message;
        updatedAt = now;
    }

    /** Interrupted by a restart: parsing restarts are unsafe (cost, crash loops), applying may be retried. */
    public void interrupted(Instant now) {
        if (status == ImportJobStatus.PARSING) {
            status = ImportJobStatus.FAILED;
            error = "Обробку перервано перезапуском сервісу. Завантажте файл ще раз.";
        } else if (status == ImportJobStatus.APPLYING) {
            status = ImportJobStatus.FAILED;
            error = "Застосування перервано перезапуском сервісу. Натисніть «Застосувати» ще раз — "
                    + "вже виконані кроки не повторюються.";
        }
        updatedAt = now;
    }

    /** Waiting for review, or failed while applying: the parsed draft is intact and can be corrected. */
    public boolean acceptsMappings() {
        return status == ImportJobStatus.NEEDS_REVIEW || status == ImportJobStatus.READY
                || status == ImportJobStatus.FAILED && draft != null && parsedAt != null;
    }

    /** READY, or FAILED while applying (the draft is intact and nothing blocks). */
    public boolean canApply() {
        return status == ImportJobStatus.READY
                || status == ImportJobStatus.FAILED && draft != null && parsedAt != null && !summary.blocking();
    }

    private void updateReport(List<ReportRow> entityRows) {
        List<ReportRow> rows = new ArrayList<>(entityRows);
        rows.addAll(issues);
        rows.sort(Comparator.comparingInt(ImportJob::rank));
        this.report = List.copyOf(rows);
        this.summary = ImportSummary.of(entityRows, rows);
    }

    /** Rows that need the admin first: errors, undecided duplicates, warnings, info, matched. */
    private static int rank(ReportRow row) {
        if (row.severity() == Severity.ERROR) {
            return 0;
        }
        if (row.needsDecision()) {
            return 1;
        }
        return switch (row.severity()) {
            case WARN -> 2;
            case INFO -> 3;
            default -> 4;
        };
    }

    private void require(boolean condition, String action) {
        if (!condition) {
            throw new IllegalStateException("Cannot " + action + " import job " + id + " in status " + status);
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }

    public UUID id() {
        return id;
    }

    public ImportKind kind() {
        return kind;
    }

    public ImportJobStatus status() {
        return status;
    }

    public String fileName() {
        return fileName;
    }

    public String contentType() {
        return contentType;
    }

    public FileType fileType() {
        return fileType;
    }

    public long fileSize() {
        return fileSize;
    }

    public String sha256() {
        return sha256;
    }

    public ImportProperties.Storage storage() {
        return storage;
    }

    public String storagePath() {
        return storagePath;
    }

    public UUID semesterId() {
        return semesterId;
    }

    public JsonNode options() {
        return options;
    }

    public JsonNode draft() {
        return draft;
    }

    public List<ReportRow> issues() {
        return issues == null ? List.of() : issues;
    }

    public List<ReportRow> report() {
        return report == null ? List.of() : report;
    }

    public ImportSummary summary() {
        return summary;
    }

    public List<MappingDecision> mappings() {
        return mappings == null ? List.of() : mappings;
    }

    public AiJobUsage ai() {
        return ai;
    }

    public JsonNode result() {
        return result;
    }

    public String error() {
        return error;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant parsedAt() {
        return parsedAt;
    }

    public Instant appliedAt() {
        return appliedAt;
    }

    public long version() {
        return version;
    }
}
