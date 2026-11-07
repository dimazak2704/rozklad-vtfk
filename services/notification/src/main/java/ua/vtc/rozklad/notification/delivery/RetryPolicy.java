package ua.vtc.rozklad.notification.delivery;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.DeliveryProperties;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/**
 * What to do after a failed attempt:
 * <ul>
 *   <li>429 — wait exactly {@code retry_after} (not counted as a failed attempt);</li>
 *   <li>5xx, network, temporary local errors — exponential backoff {@code base·2^(n-1)} capped at
 *       {@code backoffMax}, FAILED after {@code maxAttempts};</li>
 *   <li>group upgraded to a supergroup — resend to the new chat id;</li>
 *   <li>other 4xx (bad request, bot blocked/kicked, message gone) — FAILED at once.</li>
 * </ul>
 */
@Component
public class RetryPolicy {

    private final DeliveryProperties properties;

    public RetryPolicy(DeliveryProperties properties) {
        this.properties = properties;
    }

    /** @param attempt number of the attempt that just failed (1 for the first) */
    public Decision onTelegramFailure(TelegramApiFailure failure, int attempt, Instant now) {
        if (failure.migrateToChatId() != null) {
            return new Decision.Migrate(failure.migrateToChatId());
        }
        if (failure.isRateLimited()) {
            int seconds = failure.retryAfterSeconds() == null ? 1 : Math.max(1, failure.retryAfterSeconds());
            return new Decision.Retry(now.plusSeconds(seconds), false);
        }
        if (failure.isServerError() || failure.isNetwork()) {
            return transientFailure(attempt, now);
        }
        return new Decision.Fail();
    }

    /** Failures on our side that may pass (e.g. rendering the order picture). */
    public Decision onTransientFailure(int attempt, Instant now) {
        return transientFailure(attempt, now);
    }

    private Decision transientFailure(int attempt, Instant now) {
        if (attempt >= properties.maxAttempts()) {
            return new Decision.Fail();
        }
        return new Decision.Retry(now.plus(backoff(attempt)), true);
    }

    /** {@code base · 2^(attempt-1)}, at most {@code backoffMax}. */
    Duration backoff(int attempt) {
        Duration delay = properties.backoffBase();
        for (int i = 1; i < attempt; i++) {
            delay = delay.multipliedBy(2);
            if (delay.compareTo(properties.backoffMax()) >= 0) {
                return properties.backoffMax();
            }
        }
        return delay.compareTo(properties.backoffMax()) > 0 ? properties.backoffMax() : delay;
    }

    public sealed interface Decision {

        /** @param countsAsAttempt false for 429: waiting for Telegram is not our failure */
        record Retry(Instant at, boolean countsAsAttempt) implements Decision {
        }

        record Fail() implements Decision {
        }

        record Migrate(long newChatId) implements Decision {
        }
    }
}
