package ua.vtc.rozklad.importer.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Base URLs of other services ({@code CORE_URL}). */
@Validated
@ConfigurationProperties("app.services")
public record ServiceUrlsProperties(@NotNull URI coreUrl) {
}
