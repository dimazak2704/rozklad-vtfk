package ua.vtc.rozklad.notification.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Outbound queue ({@code app.delivery.*}). Telegram limits: about 30 messages per second for the
 * bot, about 20 per minute in one group chat and roughly one per second in a private chat; the
 * defaults stay below them.
 *
 * @param workerEnabled        run the background worker (off in tests: they drain the queue explicitly)
 * @param pollInterval         pause between queue polls
 * @param batchSize            messages claimed per poll
 * @param lease                how long a claimed message stays invisible to other workers
 * @param maxAttempts          attempts for 5xx/network failures before the message is FAILED
 * @param backoffBase          first retry delay; doubles with every attempt
 * @param backoffMax           upper bound of the retry delay
 * @param maxAge               a message not delivered within this time is FAILED as stale
 * @param globalPerSecond      token bucket of the whole bot
 * @param groupChatPerMinute   token bucket of one group chat
 * @param privateChatPerSecond token bucket of one private chat
 * @param burst                bucket capacity of one chat
 * @param retention            SENT/FAILED rows older than this are deleted
 */
@Validated
@ConfigurationProperties("app.delivery")
public record DeliveryProperties(
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("500ms") @NotNull Duration pollInterval,
        @DefaultValue("20") @Min(1) @Max(200) int batchSize,
        @DefaultValue("60s") @NotNull Duration lease,
        @DefaultValue("8") @Min(1) @Max(30) int maxAttempts,
        @DefaultValue("2s") @NotNull Duration backoffBase,
        @DefaultValue("10m") @NotNull Duration backoffMax,
        @DefaultValue("24h") @NotNull Duration maxAge,
        @DefaultValue("25") @Min(1) int globalPerSecond,
        @DefaultValue("18") @Min(1) int groupChatPerMinute,
        @DefaultValue("1") @Min(1) int privateChatPerSecond,
        @DefaultValue("3") @Min(1) int burst,
        @DefaultValue("30d") @NotNull Duration retention) {
}
