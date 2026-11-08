package ua.vtc.rozklad.notification.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bot behaviour ({@code app.bot.*}).
 *
 * @param dialogTtl            how long a half-finished dialog (search, absence wizard) is kept in Redis
 * @param digestEnabled        morning digest for subscribers on study days
 * @param codeTtl              lifetime of one-time binding codes
 * @param noTelegramNoticeDelay how long a request of a teacher without Telegram may stay unanswered before
 *                             admins are told: changes confirmed or published within this time (a seeded or
 *                             quickly confirmed order) cause no warning at all
 * @param adminCheckTtl        how long the answer of {@code getChatMember} («is this user a chat admin?») is reused
 * @param bindAttempts         failed binding codes a chat (and a user) may send within {@code bindAttemptsWindow}
 * @param bindAttemptsWindow   window of the failed-attempt counter; after it the counter starts again
 */
@Validated
@ConfigurationProperties("app.bot")
public record BotProperties(
        @DefaultValue("30m") @NotNull Duration dialogTtl,
        @DefaultValue("true") boolean digestEnabled,
        @DefaultValue("24h") @NotNull Duration codeTtl,
        @DefaultValue("2m") @NotNull Duration noTelegramNoticeDelay,
        @DefaultValue("1m") @NotNull Duration adminCheckTtl,
        @DefaultValue("5") @Positive int bindAttempts,
        @DefaultValue("15m") @NotNull Duration bindAttemptsWindow) {
}
