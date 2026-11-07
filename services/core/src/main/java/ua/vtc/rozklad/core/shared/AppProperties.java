package ua.vtc.rozklad.core.shared;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * General application settings ({@code app.*}).
 *
 * @param publicWebUrl base URL of the SPA for links in invites and bot messages ({@code PUBLIC_WEB_URL})
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(@NotNull URI publicWebUrl) {
}
