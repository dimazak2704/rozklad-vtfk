package ua.vtc.rozklad.importer.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.importer.ai.AiJobUsage;
import ua.vtc.rozklad.importer.normalize.MappingDecision;
import ua.vtc.rozklad.importer.report.ImportSummary;
import ua.vtc.rozklad.importer.report.ReportRow;

/**
 * CONTRACTS §5 {@code ImportJobDto} plus additive fields: {@code fileType}, {@code fileSize},
 * {@code updatedAt}, {@code createdBy}, {@code semesterId}, {@code options}, {@code mappings},
 * {@code result} (core ids after applying), {@code canApply} and {@code version} (sent back when the draft
 * is edited). In lists {@code report} is empty
 * and {@code draft}, {@code options}, {@code mappings}, {@code result} are omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportJobDto(UUID id, ImportKind kind, String fileName, FileType fileType, long fileSize,
                           ImportJobStatus status, Instant createdAt, Instant updatedAt, String createdBy, UUID semesterId,
                           ImportSummary summary, List<ReportRow> report, JsonNode draft, JsonNode options,
                           List<MappingDecision> mappings, AiJobUsage ai, JsonNode result, String error, boolean canApply,
                           Long version) {

    public static ImportJobDto of(ImportJob job) {
        return new ImportJobDto(job.id(), job.kind(), job.fileName(), job.fileType(), job.fileSize(), job.status(),
                job.createdAt(), job.updatedAt(), job.createdBy(), job.semesterId(), job.summary(), job.report(), job.draft(),
                job.options(), job.mappings(), job.ai(), job.result(), job.error(), job.canApply(), job.version());
    }

    public static ImportJobDto of(ImportJobRepository.ListItem item) {
        boolean canApply = item.status() == ImportJobStatus.READY;
        return new ImportJobDto(item.id(), item.kind(), item.fileName(), item.fileType(), item.fileSize(), item.status(),
                item.createdAt(), item.updatedAt(), item.createdBy(), item.semesterId(), item.summary(), List.of(), null,
                null, null, item.ai(), null, item.error(), canApply, null);
    }
}
