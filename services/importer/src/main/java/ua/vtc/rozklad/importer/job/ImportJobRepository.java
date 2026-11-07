package ua.vtc.rozklad.importer.job;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ua.vtc.rozklad.importer.ai.AiJobUsage;
import ua.vtc.rozklad.importer.report.ImportSummary;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {

    /** Light list row: everything except the draft and the report (they can be hundreds of KB). */
    record ListItem(UUID id, ImportKind kind, String fileName, FileType fileType, long fileSize, ImportJobStatus status,
                    UUID semesterId, ImportSummary summary, AiJobUsage ai, String error, String createdBy,
                    Instant createdAt, Instant updatedAt) {
    }

    @Query("""
            select new ua.vtc.rozklad.importer.job.ImportJobRepository$ListItem(
                j.id, j.kind, j.fileName, j.fileType, j.fileSize, j.status, j.semesterId, j.summary, j.ai, j.error,
                j.createdBy, j.createdAt, j.updatedAt)
            from ImportJob j
            where (:kind is null or j.kind = :kind) and (:status is null or j.status = :status)
            """)
    Page<ListItem> search(@Param("kind") ImportKind kind, @Param("status") ImportJobStatus status, Pageable pageable);

    List<ImportJob> findByStatusIn(Collection<ImportJobStatus> statuses);
}
