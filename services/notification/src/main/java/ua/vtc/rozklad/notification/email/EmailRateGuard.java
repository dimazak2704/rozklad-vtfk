package ua.vtc.rozklad.notification.email;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.config.StandClock;
import ua.vtc.rozklad.notification.shared.TokenBuckets;
import ua.vtc.rozklad.notification.shared.TokenBuckets.Bucket;

/**
 * Limits of the e-mail channel in Redis (shared by all instances): the whole service ~2 letters/s, one address
 * ~20/hour with a burst of 5 (wait, no attempt spent), and a hard cap per address and day that turns a runaway
 * loop into FAILED letters. Keys hold a SHA-256 prefix of the address, never the address. Redis down ⇒ letters go
 * out without local limits (logged once a minute), as in Telegram.
 */
@Component
public class EmailRateGuard {

    static final String GLOBAL_KEY = "rozklad:notification:mail:global";
    static final String RECIPIENT_PREFIX = "rozklad:notification:mail:to:";
    static final String DAY_PREFIX = "rozklad:notification:mail:day:";
    static final String TEST_PREFIX = "rozklad:notification:mail:test:";

    private static final Logger log = LoggerFactory.getLogger(EmailRateGuard.class);
    private static final long WARN_EVERY_MS = 60_000;

    private final TokenBuckets buckets;
    private final StringRedisTemplate redis;
    private final MailChannelProperties properties;
    private final Clock clock;
    private final AtomicLong lastWarning = new AtomicLong();

    public EmailRateGuard(TokenBuckets buckets, StringRedisTemplate redis, MailChannelProperties properties,
                          Clock clock) {
        this.buckets = buckets;
        this.redis = redis;
        this.properties = properties;
        this.clock = StandClock.real(clock);
    }

    /** @return zero when a letter to {@code address} may go now, otherwise how long to wait */
    public Duration tryAcquire(String address) {
        long now = clock.millis();
        try {
            return buckets.tryAcquire(List.of(
                    new Bucket(GLOBAL_KEY, properties.globalPerSecond(), properties.globalPerSecond() / 1000.0),
                    new Bucket(RECIPIENT_PREFIX + MailAddresses.fingerprint(address), properties.recipientBurst(),
                            properties.recipientPerHour() / 3_600_000.0)), now, Duration.ofHours(2));
        } catch (DataAccessException e) {
            warn("rate limits", e);
            return Duration.ZERO;
        }
    }

    /** Counts a letter to {@code address} today (UTC); false once the daily cap is passed. */
    public boolean withinDailyCap(String address) {
        String key = DAY_PREFIX + MailAddresses.fingerprint(address) + ":" + LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return count(key, Duration.ofDays(2)) <= properties.recipientPerDay();
    }

    /** Counts a test letter of {@code accountId} in the current hour; false over the limit. */
    public boolean mayRequestTestLetter(UUID accountId) {
        long hour = clock.millis() / 3_600_000L;
        return count(TEST_PREFIX + accountId + ":" + hour, Duration.ofHours(2)) <= properties.testLettersPerHour();
    }

    private long count(String key, Duration keep) {
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1) {
                redis.expire(key, keep);
            }
            return value == null ? 0 : value;
        } catch (DataAccessException e) {
            warn("counters", e);
            return 0;
        }
    }

    private void warn(String what, DataAccessException e) {
        long now = clock.millis();
        long last = lastWarning.get();
        if (now - last > WARN_EVERY_MS && lastWarning.compareAndSet(last, now)) {
            log.warn("E-mail {} unavailable (Redis): sending without them: {}", what, e.getMessage());
        }
    }
}
