package ua.vtc.rozklad.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.support.MutableClock;

/** Token buckets in real Redis with the production limits: 25/s bot, 18/min group, 1/s private, burst 3. */
class RedisRateLimiterIT extends IntegrationTest {

    @Autowired
    private StringRedisTemplate redis;

    private final MutableClock time = new MutableClock();

    @Test
    void privateChatAllowsABurstThenAboutOneMessagePerSecond() {
        RedisRateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(42)).isZero();
        }
        assertThat(limiter.tryAcquire(42)).isBetween(Duration.ofMillis(999), Duration.ofMillis(1001));
        time.advance(Duration.ofMillis(500));
        assertThat(limiter.tryAcquire(42)).isBetween(Duration.ofMillis(499), Duration.ofMillis(501));
        time.advance(Duration.ofMillis(501));
        assertThat(limiter.tryAcquire(42)).isZero();
        assertThat(limiter.tryAcquire(42)).isPositive();
    }

    @Test
    void groupChatRefillsEighteenPerMinute() {
        RedisRateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(-100500)).isZero();
        }
        assertThat(limiter.tryAcquire(-100500)).isBetween(Duration.ofMillis(3333), Duration.ofMillis(3335));
        // інший груповий чат має власне відро
        assertThat(limiter.tryAcquire(-100501)).isZero();
    }

    @Test
    void theWholeBotIsLimitedToTwentyFivePerSecond() {
        RedisRateLimiter limiter = limiter();
        LongStream.rangeClosed(1, 25).forEach(chat -> assertThat(limiter.tryAcquire(chat)).isZero());
        assertThat(limiter.tryAcquire(26)).isBetween(Duration.ofMillis(39), Duration.ofMillis(41));
        time.advance(Duration.ofMillis(41));
        assertThat(limiter.tryAcquire(26)).isZero();
    }

    @Test
    void aRefusedAttemptTakesNoTokens() {
        RedisRateLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(7);
        }
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(7)).isPositive();
        }
        time.advance(Duration.ofMillis(1000));
        assertThat(limiter.tryAcquire(7)).isZero();
        assertThat(limiter.tryAcquire(7)).isPositive();
    }

    private RedisRateLimiter limiter() {
        return new RedisRateLimiter(redis, time, RetryPolicyTest.properties(8, Duration.ofSeconds(2),
                Duration.ofMinutes(10)));
    }
}
