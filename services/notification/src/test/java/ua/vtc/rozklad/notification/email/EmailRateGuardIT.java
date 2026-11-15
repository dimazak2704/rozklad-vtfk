package ua.vtc.rozklad.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import ua.vtc.rozklad.notification.shared.TokenBuckets;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.support.MailTestProperties;
import ua.vtc.rozklad.notification.support.MutableClock;

/** The e-mail limits in real Redis with the production defaults: 2/s overall, 20/hour per address, 60/day. */
class EmailRateGuardIT extends IntegrationTest {

    @Autowired
    private TokenBuckets buckets;
    @Autowired
    private StringRedisTemplate redis;

    private final MutableClock time = new MutableClock();

    @Test
    void theWholeServiceSendsAboutTwoLettersASecond() {
        EmailRateGuard guard = guard();
        assertThat(guard.tryAcquire("a@vtc.vn.ua")).isZero();
        assertThat(guard.tryAcquire("b@vtc.vn.ua")).isZero();
        assertThat(guard.tryAcquire("c@vtc.vn.ua")).isBetween(Duration.ofMillis(499), Duration.ofMillis(501));
        time.advance(Duration.ofMillis(500));
        assertThat(guard.tryAcquire("c@vtc.vn.ua")).isZero();
    }

    @Test
    void oneAddressGetsABurstOfFiveThenTwentyAnHour() {
        EmailRateGuard guard = guard();
        for (int i = 0; i < 5; i++) {
            time.advance(Duration.ofSeconds(1));
            assertThat(guard.tryAcquire("k@vtc.vn.ua")).isZero();
        }
        time.advance(Duration.ofSeconds(1));
        // 20 per hour = one every 3 minutes; ~5 s of refill already passed
        assertThat(guard.tryAcquire("k@vtc.vn.ua")).isBetween(Duration.ofSeconds(170), Duration.ofSeconds(180));
        assertThat(guard.tryAcquire("other@vtc.vn.ua")).isZero();
        assertThat(redis.keys("rozklad:notification:mail:*")).noneMatch(key -> key.contains("vtc.vn.ua"));
    }

    @Test
    void aRunawayLoopStopsAtTheDailyCap() {
        EmailRateGuard guard = guard();
        for (int i = 0; i < 60; i++) {
            assertThat(guard.withinDailyCap("k@vtc.vn.ua")).isTrue();
        }
        assertThat(guard.withinDailyCap("k@vtc.vn.ua")).isFalse();
        assertThat(guard.withinDailyCap("other@vtc.vn.ua")).isTrue();
    }

    @Test
    void threeTestLettersAnHourPerAccount() {
        EmailRateGuard guard = guard();
        UUID account = UUID.randomUUID();
        assertThat(guard.mayRequestTestLetter(account)).isTrue();
        assertThat(guard.mayRequestTestLetter(account)).isTrue();
        assertThat(guard.mayRequestTestLetter(account)).isTrue();
        assertThat(guard.mayRequestTestLetter(account)).isFalse();
        assertThat(guard.mayRequestTestLetter(UUID.randomUUID())).isTrue();
    }

    private EmailRateGuard guard() {
        return new EmailRateGuard(buckets, redis, MailTestProperties.of(), time);
    }
}
