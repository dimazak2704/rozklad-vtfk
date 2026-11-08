package ua.vtc.rozklad.importer.job;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.report.ReportRow;

/**
 * What differs between import kinds: accepted files and options, parsing to a draft, the catalog
 * entities of the draft and applying it through core REST. The job lifecycle around it is shared.
 */
public interface ImportHandler {

    ImportKind kind();

    /**
     * Upload-time checks (file type, options).
     *
     * @return {@code true} when parsing will call AI (then AI availability and budget are checked first)
     * @throws ua.vtc.rozklad.importer.web.ProblemException          415 for a wrong file type
     * @throws ua.vtc.rozklad.importer.web.InvalidRequestException   400 for missing/invalid options
     */
    boolean acceptUpload(FileType type, JobOptions options);

    /** Parses the file; throws {@link ImportParseException} with a Ukrainian message when it cannot. */
    ParseOutcome parse(ParseInput input);

    /** Entities of the draft that are matched against the catalog and can be mapped by the admin. */
    List<EntityCandidate> entities(JsonNode draft, JobOptions options);

    /** Applies the draft through core; returns the combined result of all steps. */
    JsonNode apply(ApplyInput input);

    /**
     * Whether undecided possible duplicates block applying. Kinds that send only text snapshots
     * (orders) report matching for information and return {@code false}.
     */
    default boolean entitiesBlockApply() {
        return true;
    }

    /**
     * Whether applying creates a catalog entry for a {@code type} value that is not in the catalog.
     * When it does not (orders keep text only; load imports add hours to existing groups), the
     * report never promises «буде створено» and an admin cannot ask to create one.
     */
    default boolean creates(EntityType type) {
        return true;
    }

    /**
     * What applying needs beyond the matching rows, checked whenever the report is rebuilt (parse,
     * mappings, options, draft revision): a blocking ERROR row here keeps the job in review instead
     * of letting it fail at apply.
     */
    default List<ReportRow> readiness(JsonNode draft, JobOptions options, NormalizationResult normalization) {
        return List.of();
    }

    record ParseInput(UUID jobId, String fileName, FileType type, byte[] content, JobOptions options) {
    }

    record ParseOutcome(Object draft, List<ReportRow> issues) {

        public ParseOutcome {
            issues = List.copyOf(issues);
        }
    }

    record ApplyInput(UUID jobId, String fileName, JsonNode draft, JobOptions options, UUID semesterId,
                      NormalizationResult normalization, CatalogSnapshot catalog, ApplySteps steps) {
    }

    /** Checkpoints of applying: a step done by an earlier attempt is not repeated. */
    interface ApplySteps {

        /** Result of a finished step, or {@code null}. */
        JsonNode done(String step);

        /** Stores a finished step right away (own transaction). */
        void finished(String step, JsonNode result);

        /** {@code import-job:<id>:<step>} for core-side deduplication. */
        String idempotencyKey(String step);

        /** All steps finished so far. */
        JsonNode all();
    }
}
