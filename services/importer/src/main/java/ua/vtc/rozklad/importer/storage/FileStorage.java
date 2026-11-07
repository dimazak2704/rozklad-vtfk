package ua.vtc.rozklad.importer.storage;

import java.util.UUID;
import ua.vtc.rozklad.importer.config.ImportProperties;

/** Keeps the bytes of uploaded files; the job row stores where they are. */
public interface FileStorage {

    ImportProperties.Storage kind();

    /**
     * Stores the content of a job's file within the caller's transaction.
     *
     * @return the storage path for {@code FILESYSTEM}, {@code null} for {@code DATABASE}
     */
    String store(UUID jobId, byte[] content);

    /** Loads the content; {@code storagePath} is what {@link #store} returned. */
    byte[] load(UUID jobId, String storagePath);
}
