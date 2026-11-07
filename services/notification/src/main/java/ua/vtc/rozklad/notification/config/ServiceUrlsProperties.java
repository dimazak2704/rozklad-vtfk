package ua.vtc.rozklad.notification.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Base URLs: other services ({@code CORE_URL}, {@code IMPORTER_URL}) and the SPA for links in bot
 * messages ({@code PUBLIC_WEB_URL}).
 */
@Validated
@ConfigurationProperties("app.services")
public record ServiceUrlsProperties(@NotNull URI coreUrl, @NotNull URI importerUrl, @NotNull URI publicWebUrl) {
}
