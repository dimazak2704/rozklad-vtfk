package ua.vtc.rozklad.importer.storage;

import java.util.UUID;
import ua.vtc.rozklad.importer.config.ImportProperties;

/** Stores file bytes in {@code import_file.content} (bytea) next to the job. */
public class DatabaseFileStorage implements FileStorage {

    private final ImportFileRepository files;

    public DatabaseFileStorage(ImportFileRepository files) {
        this.files = files;
    }

    @Override
    public ImportProperties.Storage kind() {
        return ImportProperties.Storage.DATABASE;
    }

    @Override
    public String store(UUID jobId, byte[] content) {
        files.save(new ImportFile(jobId, content));
        return null;
    }

    @Override
    public byte[] load(UUID jobId, String storagePath) {
        return files.findById(jobId)
                .map(ImportFile::content)
                .orElseThrow(() -> new StoredFileMissingException("Файл завдання не знайдено в базі даних."));
    }
}
