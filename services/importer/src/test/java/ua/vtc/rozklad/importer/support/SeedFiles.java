package ua.vtc.rozklad.importer.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real college files from the repository's {@code seed/} directory (tests run in services/importer). */
public final class SeedFiles {

    private SeedFiles() {
    }

    public static Path path(String relative) {
        Path seed = Path.of(System.getProperty("user.dir")).resolve("../../seed").normalize();
        Path file = seed.resolve(relative);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Seed file not found: " + file);
        }
        return file;
    }

    public static byte[] read(String relative) {
        try {
            return Files.readAllBytes(path(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
