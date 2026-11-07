package ua.vtc.rozklad.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.config.DeliveryProperties;
import ua.vtc.rozklad.notification.delivery.RetryPolicy.Decision;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");

    private final RetryPolicy policy = new RetryPolicy(properties(5, Duration.ofSeconds(2), Duration.ofSeconds(30)));

    @Test
    void tooManyRequestsWaitsExactlyRetryAfterWithoutSpendingAnAttempt() {
        Decision decision = policy.onTelegramFailure(failure(429, "Too Many Requests: retry after 7", 7, null), 1, NOW);
        assertThat(decision).isEqualTo(new Decision.Retry(NOW.plusSeconds(7), false));
        // навіть після вичерпаних спроб 429 лише відкладає
        assertThat(policy.onTelegramFailure(failure(429, "flood", 3, null), 99, NOW))
                .isEqualTo(new Decision.Retry(NOW.plusSeconds(3), false));
        assertThat(policy.onTelegramFailure(failure(429, "flood", null, null), 1, NOW))
                .isEqualTo(new Decision.Retry(NOW.plusSeconds(1), false));
    }

    @Test
    void serverErrorsAndNetworkBackOffExponentiallyUpToTheCap() {
        assertThat(policy.onTelegramFailure(failure(502, "Bad Gateway", null, null), 1, NOW))
                .isEqualTo(new Decision.Retry(NOW.plusSeconds(2), true));
        assertThat(policy.onTelegramFailure(failure(500, "Internal", null, null), 2, NOW))
                .isEqualTo(new Decision.Retry(NOW.plusSeconds(4), true));
        assertThat(policy.onTelegramFailure(TelegramApiFailure.network("timeout", null), 3, NOW))
                .isEqualTo(new Decision.Retry(NOW.plusSeconds(8), true));
        assertThat(policy.backoff(4)).isEqualTo(Duration.ofSeconds(16));
        assertThat(policy.backoff(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoff(20)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void attemptsRunOutIntoFailure() {
        assertThat(policy.onTelegramFailure(failure(503, "Unavailable", null, null), 5, NOW))
                .isInstanceOf(Decision.Fail.class);
        assertThat(policy.onTransientFailure(5, NOW)).isInstanceOf(Decision.Fail.class);
        assertThat(policy.onTransientFailure(1, NOW)).isEqualTo(new Decision.Retry(NOW.plusSeconds(2), true));
    }

    @Test
    void clientErrorsFailAtOnce() {
        assertThat(policy.onTelegramFailure(failure(403, "Forbidden: bot was blocked by the user", null, null), 1,
                NOW)).isInstanceOf(Decision.Fail.class);
        assertThat(policy.onTelegramFailure(failure(400, "Bad Request: chat not found", null, null), 1, NOW))
                .isInstanceOf(Decision.Fail.class);
    }

    @Test
    void upgradedGroupMovesTheMessageToTheSupergroup() {
        TelegramApiFailure upgraded = failure(400, "Bad Request: group chat was upgraded to a supergroup chat", null,
                -1001234567890L);
        assertThat(policy.onTelegramFailure(upgraded, 1, NOW)).isEqualTo(new Decision.Migrate(-1001234567890L));
    }

    @Test
    void failureClassification() {
        assertThat(failure(400, "Bad Request: message is not modified: specified new message content", null, null)
                .isMessageNotModified()).isTrue();
        assertThat(failure(400, "Bad Request: chat not found", null, null).isMessageNotModified()).isFalse();
        assertThat(TelegramApiFailure.network("x", null).isNetwork()).isTrue();
        assertThat(failure(403, "Forbidden", null, null).isForbidden()).isTrue();
    }

    private static TelegramApiFailure failure(int code, String description, Integer retryAfter, Long migrateTo) {
        return new TelegramApiFailure(code, description, retryAfter, migrateTo, null);
    }

    static DeliveryProperties properties(int maxAttempts, Duration base, Duration max) {
        return new DeliveryProperties(false, Duration.ofMillis(500), 20, Duration.ofSeconds(60), maxAttempts, base, max,
                Duration.ofHours(24), 25, 18, 1, 3, Duration.ofDays(30));
    }
}
