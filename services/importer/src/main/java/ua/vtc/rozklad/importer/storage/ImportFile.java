package ua.vtc.rozklad.importer.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** File content in the database (storage mode {@code DATABASE}); one row per job. */
@Entity
@Table(name = "import_file")
public class ImportFile implements Persistable<UUID> {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "content", nullable = false)
    private byte[] content;

    protected ImportFile() {
    }

    public ImportFile(UUID jobId, byte[] content) {
        this.jobId = jobId;
        this.content = content.clone();
    }

    public byte[] content() {
        return content.clone();
    }

    @Override
    public UUID getId() {
        return jobId;
    }

    /** Rows are written once and never updated: always a new entity for {@code save}. */
    @Override
    public boolean isNew() {
        return true;
    }
}
