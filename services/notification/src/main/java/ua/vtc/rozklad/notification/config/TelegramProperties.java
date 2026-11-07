package ua.vtc.rozklad.notification.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Telegram bot settings ({@code app.telegram.*}). An empty {@code botToken} disables the bot
 * (the service still renders documents). Token and webhook secret are never logged.
 *
 * @param ownerChatId   chat for {@code POST /api/notify/test} and admin notifications
 * @param mode          POLLING (default) or WEBHOOK
 * @param webhookSecret value of {@code X-Telegram-Bot-Api-Secret-Token} in WEBHOOK mode
 */
@Validated
@ConfigurationProperties("app.telegram")
public record TelegramProperties(String botToken, String botUsername, Long ownerChatId, @NotNull Mode mode,
                                 String webhookSecret) {

    public enum Mode {
        POLLING,
        WEBHOOK
    }

    public boolean enabled() {
        return botToken != null && !botToken.isBlank();
    }

    @Override
    public String toString() {
        return "TelegramProperties[botToken=" + (enabled() ? "***" : "<empty>") + ", botUsername=" + botUsername
                + ", ownerChatId=" + (ownerChatId == null ? "<empty>" : "***") + ", mode=" + mode
                + ", webhookSecret=***]";
    }
}
