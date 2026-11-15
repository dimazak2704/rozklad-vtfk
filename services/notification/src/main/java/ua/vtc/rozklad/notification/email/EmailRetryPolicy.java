package ua.vtc.rozklad.notification.email;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.MailChannelProperties;

/**
 * What to do after a failed attempt:
 * <ul>
 *   <li>the server refused the recipient (5xx), or the letter could not be built — FAILED at once;</li>
 *   <li>connection problems, timeouts, 4xx replies, a refused login (a configuration problem an admin fixes) —
 *       backoff {@code base·2^(n-1)} capped at {@code backoffMax}, FAILED after {@code maxAttempts};</li>
 *   <li>core unreachable while the recipients of an intent are resolved — the same backoff.</li>
 * </ul>
 */
@Component
public class EmailRetryPolicy {

    private final MailChannelProperties properties;

    public EmailRetryPolicy(MailChannelProperties properties) {
        this.properties = properties;
    }

    /** @param attempt number of the attempt that just failed (1 for the first) */
    public Decision onSendFailure(MailTransportException failure, int attempt, Instant now) {
        if (failure.permanent()) {
            return new Decision.Fail();
        }
        return transientFailure(attempt, now);
    }

    public Decision onTransientFailure(int attempt, Instant now) {
        return transientFailure(attempt, now);
    }

    private Decision transientFailure(int attempt, Instant now) {
        if (attempt >= properties.maxAttempts()) {
            return new Decision.Fail();
        }
        return new Decision.Retry(now.plus(backoff(attempt)));
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

        record Retry(Instant at) implements Decision {
        }

        record Fail() implements Decision {
        }
    }
}
