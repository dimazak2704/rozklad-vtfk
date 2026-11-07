package ua.vtc.rozklad.notification.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Order documents ({@code app.documents.*}).
 *
 * @param cacheDir          rendered files of published orders and the LibreOffice profile
 * @param conversionTimeout one DOCX → PDF conversion
 * @param pngScale          PDF → PNG scale (2 = 144 DPI, sharp on phones)
 */
@Validated
@ConfigurationProperties("app.documents")
public record DocumentProperties(
        @NotBlank String cacheDir,
        @DefaultValue("90s") @NotNull Duration conversionTimeout,
        @DefaultValue("2") @Min(1) @Max(4) int pngScale) {

    public Path cachePath() {
        return Path.of(cacheDir);
    }
}
