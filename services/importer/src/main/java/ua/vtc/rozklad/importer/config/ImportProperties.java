package ua.vtc.rozklad.importer.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Import settings ({@code app.import.*}).
 *
 * @param maxFileSize   upload limit (CONTRACTS §5: 20 MB)
 * @param storage       where uploaded bytes live: {@code DATABASE} (bytea) or {@code FILESYSTEM}
 * @param storageDir    directory for {@code FILESYSTEM} storage (a volume in compose)
 * @param workers       parallel parse/apply jobs (POI on a large workbook is memory-hungry)
 * @param queueCapacity jobs waiting for a worker before uploads are rejected
 */
@Validated
@ConfigurationProperties("app.import")
public record ImportProperties(@NotNull DataSize maxFileSize, @NotNull Storage storage, Path storageDir,
                               @Min(1) @Max(8) int workers, @Min(1) int queueCapacity) {

    public enum Storage {
        DATABASE, FILESYSTEM
    }
}
