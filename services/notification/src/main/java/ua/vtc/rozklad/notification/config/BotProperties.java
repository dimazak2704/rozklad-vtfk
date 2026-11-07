package ua.vtc.rozklad.notification.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bot behaviour ({@code app.bot.*}).
 *
 * @param dialogTtl      how long a half-finished dialog (search, absence wizard) is kept in Redis
 * @param digestEnabled  morning digest for subscribers on study days
 * @param codeTtl        lifetime of one-time binding codes
 */
@Validated
@ConfigurationProperties("app.bot")
public record BotProperties(
        @DefaultValue("30m") @NotNull Duration dialogTtl,
        @DefaultValue("true") boolean digestEnabled,
        @DefaultValue("24h") @NotNull Duration codeTtl) {
}
