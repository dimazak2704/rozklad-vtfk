package ua.vtc.rozklad.notification.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.telegram.TelegramApi.BotIdentity;

/** {@code GET /api/notify/bot}: who the bot is and whether it really works ({@code getMe} cached for a minute). */
@Service
public class BotStatus {

    static final Duration CHECK_EVERY = Duration.ofMinutes(1);

    private final TelegramProperties telegram;
    private final TelegramApi api;
    private final TelegramBotRunner runner;
    private final Clock clock;
    private volatile Check lastCheck;

    public BotStatus(TelegramProperties telegram, TelegramApi api, TelegramBotRunner runner, Clock clock) {
        this.telegram = telegram;
        this.api = api;
        this.runner = runner;
        this.clock = clock;
    }

    public Status status() {
        if (!telegram.enabled()) {
            return new Status(telegram.botUsername(), telegram.botLink(), telegram.mode(), false,
                    "Бот вимкнено: не задано TELEGRAM_BOT_TOKEN.");
        }
        Check check = check();
        String username = check.identity() != null && check.identity().username() != null
                ? check.identity().username()
                : telegram.botUsername();
        String problem = null;
        if (check.identity() == null) {
            problem = "Telegram недоступний або токен недійсний: " + check.error();
        } else if (!runner.isReceiving()) {
            problem = telegram.mode() == TelegramProperties.Mode.POLLING
                    ? "Бот не отримує повідомлень: long polling не запущено."
                    : "Бот не отримує повідомлень: вебхук не зареєстровано (TELEGRAM_WEBHOOK_URL/SECRET).";
        }
        return new Status(username, "https://t.me/" + username, telegram.mode(), problem == null, problem);
    }

    private Check check() {
        Instant now = clock.instant();
        Check current = lastCheck;
        if (current != null && current.at().plus(CHECK_EVERY).isAfter(now)) {
            return current;
        }
        Check fresh;
        try {
            fresh = new Check(api.getMe(), null, now);
        } catch (TelegramApiFailure failure) {
            fresh = new Check(null, failure.getMessage(), now);
        }
        lastCheck = fresh;
        return fresh;
    }

    /** {@code {username, link, mode, connected}} of the contract plus {@code problem} when not connected. */
    public record Status(String username, String link, TelegramProperties.Mode mode, boolean connected,
                         String problem) {
    }

    private record Check(BotIdentity identity, String error, Instant at) {
    }
}
