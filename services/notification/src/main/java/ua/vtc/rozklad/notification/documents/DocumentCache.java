package ua.vtc.rozklad.notification.documents;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.DocumentProperties;

/**
 * Rendered files of published orders on disk, keyed by order id, payload hash and format. A file is
 * rendered once even when several threads ask for it at the same time.
 */
@Component
public class DocumentCache {

    private static final Logger log = LoggerFactory.getLogger(DocumentCache.class);

    private final Path directory;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DocumentCache(DocumentProperties properties) {
        this.directory = properties.cachePath().resolve("orders");
    }

    public byte[] get(String key, Supplier<byte[]> renderer) {
        Optional<byte[]> cached = read(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            cached = read(key);
            if (cached.isPresent()) {
                return cached.get();
            }
            byte[] content = renderer.get();
            write(key, content);
            return content;
        } finally {
            lock.unlock();
            locks.remove(key, lock);
        }
    }

    private Optional<byte[]> read(String key) {
        Path file = directory.resolve(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.warn("Cannot read cached document {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void write(String key, byte[] content) {
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, key, ".tmp");
            Files.write(temp, content);
            try {
                Files.move(temp, directory.resolve(key), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, directory.resolve(key), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // кеш — оптимізація: документ уже сформовано, просто наступного разу сформуємо знову
            log.warn("Cannot cache document {}: {}", key, e.getMessage());
        }
    }
}
