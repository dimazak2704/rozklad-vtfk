package ua.vtc.rozklad.importer.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import ua.vtc.rozklad.importer.config.ImportProperties;

/**
 * Stores file bytes under a directory (a Docker volume): {@code <dir>/<first 2 hex of id>/<id>.bin},
 * written to a temporary file first and moved into place, so a crash never leaves half a file.
 */
public class FilesystemFileStorage implements FileStorage {

    private final Path root;

    public FilesystemFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public ImportProperties.Storage kind() {
        return ImportProperties.Storage.FILESYSTEM;
    }

    @Override
    public String store(UUID jobId, byte[] content) {
        String relative = jobId.toString().substring(0, 2) + "/" + jobId + ".bin";
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), jobId.toString(), ".part");
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store import file " + target, e);
        }
        return relative;
    }

    @Override
    public byte[] load(UUID jobId, String storagePath) {
        if (storagePath == null) {
            throw new StoredFileMissingException("Для завдання не збережено шлях до файлу.");
        }
        Path file = resolve(storagePath);
        if (!Files.isRegularFile(file)) {
            throw new StoredFileMissingException("Файл завдання не знайдено в сховищі.");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read import file " + file, e);
        }
    }

    private Path resolve(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Storage path escapes the storage directory: " + relative);
        }
        return path;
    }
}
