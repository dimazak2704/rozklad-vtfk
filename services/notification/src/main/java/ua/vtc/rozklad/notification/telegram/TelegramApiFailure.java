package ua.vtc.rozklad.notification.telegram;

import java.util.Locale;

/**
 * A failed Bot API call. {@code errorCode == null} means the request did not reach Telegram or got
 * no API response (network, timeout). {@code retryAfterSeconds} comes with 429,
 * {@code migrateToChatId} with "group chat was upgraded to a supergroup".
 */
public class TelegramApiFailure extends RuntimeException {

    private final Integer errorCode;
    private final Integer retryAfterSeconds;
    private final Long migrateToChatId;

    public TelegramApiFailure(Integer errorCode, String description, Integer retryAfterSeconds, Long migrateToChatId,
                              Throwable cause) {
        super(description == null ? "Telegram API error" : description, cause);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
        this.migrateToChatId = migrateToChatId;
    }

    public static TelegramApiFailure network(String description, Throwable cause) {
        return new TelegramApiFailure(null, description, null, null, cause);
    }

    public static TelegramApiFailure botDisabled() {
        return new TelegramApiFailure(null, "Telegram bot is disabled (TELEGRAM_BOT_TOKEN is empty)", null, null,
                null);
    }

    public Integer errorCode() {
        return errorCode;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Long migrateToChatId() {
        return migrateToChatId;
    }

    public boolean isRateLimited() {
        return errorCode != null && errorCode == 429;
    }

    public boolean isServerError() {
        return errorCode != null && errorCode >= 500;
    }

    /** The call may have failed before reaching Telegram — worth a retry. */
    public boolean isNetwork() {
        return errorCode == null;
    }

    /** Editing with the same text and keyboard: the message already shows the wanted state. */
    public boolean isMessageNotModified() {
        return errorCode != null && errorCode == 400 && descriptionContains("message is not modified");
    }

    /** The bot was blocked by the user or removed from the chat. */
    public boolean isForbidden() {
        return errorCode != null && errorCode == 403;
    }

    private boolean descriptionContains(String fragment) {
        String message = getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(fragment);
    }
}
