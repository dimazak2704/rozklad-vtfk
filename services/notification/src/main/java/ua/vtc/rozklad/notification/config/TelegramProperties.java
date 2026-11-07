package ua.vtc.rozklad.notification.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Telegram bot settings ({@code app.telegram.*}). An empty {@code botToken} disables the bot
 * (the service still renders documents). Token and webhook secret are never logged.
 *
 * @param ownerChatId   chat for {@code POST /api/notify/test}; registered as ADMIN_CHAT on startup
 * @param mode          POLLING (default) or WEBHOOK
 * @param webhookSecret value of {@code X-Telegram-Bot-Api-Secret-Token} in WEBHOOK mode
 * @param webhookUrl    public HTTPS URL of {@code /api/notify/telegram/webhook}; empty ⇒ the webhook
 *                      is not registered (updates are not received)
 * @param access        who the bot talks to: BOUND_ONLY (owner chat and chats bound through the
 *                      system) or OPEN (anyone who writes to the bot)
 */
@Validated
@ConfigurationProperties("app.telegram")
public record TelegramProperties(String botToken, @NotBlank String botUsername, Long ownerChatId, @NotNull Mode mode,
                                 String webhookSecret, String webhookUrl,
                                 @DefaultValue("BOUND_ONLY") @NotNull Access access) {

    public enum Mode {
        POLLING,
        WEBHOOK
    }

    public enum Access {
        BOUND_ONLY,
        OPEN
    }

    public boolean enabled() {
        return botToken != null && !botToken.isBlank();
    }

    public boolean hasOwnerChat() {
        return ownerChatId != null;
    }

    public boolean isOwnerChat(long chatId) {
        return ownerChatId != null && ownerChatId == chatId;
    }

    /** {@code https://t.me/scheduler_vtfk_bot}. */
    public String botLink() {
        return "https://t.me/" + botUsername;
    }

    @Override
    public String toString() {
        return "TelegramProperties[botToken=" + (enabled() ? "***" : "<empty>") + ", botUsername=" + botUsername
                + ", ownerChatId=" + (ownerChatId == null ? "<empty>" : "***") + ", mode=" + mode
                + ", webhookSecret=***, webhookUrl=" + (webhookUrl == null || webhookUrl.isBlank() ? "<empty>" : "***")
                + ", access=" + access + "]";
    }
}
