package ua.vtc.rozklad.notification.delivery;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.DeliveryProperties;
import ua.vtc.rozklad.notification.config.StandClock;
import ua.vtc.rozklad.notification.shared.ChatIds;
import ua.vtc.rozklad.notification.shared.TokenBuckets;

/**
 * Token buckets in Redis (shared by all instances, {@link TokenBuckets}), checked and taken atomically:
 * global ~25 msg/s, a group chat ~18 msg/min, a private chat ~1 msg/s, each chat with a small burst.
 * If Redis is down, sending is allowed (logged): Telegram's own 429 then throttles us via
 * {@link RetryPolicy}.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    static final String GLOBAL_KEY = "rozklad:notification:ratelimit:global";
    static final String CHAT_KEY_PREFIX = "rozklad:notification:ratelimit:chat:";

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final long WARN_EVERY_MS = 60_000;
    private static final Duration KEEP = Duration.ofHours(1);

    private final TokenBuckets buckets;
    private final Clock clock;
    private final Bucket global;
    private final Bucket groupChat;
    private final Bucket privateChat;
    private final AtomicLong lastWarning = new AtomicLong();

    public RedisRateLimiter(TokenBuckets buckets, Clock clock, DeliveryProperties properties) {
        this.buckets = buckets;
        this.clock = StandClock.real(clock); // real time, not the stand time
        this.global = new Bucket(properties.globalPerSecond(), properties.globalPerSecond() / 1000.0);
        this.groupChat = new Bucket(properties.burst(), properties.groupChatPerMinute() / 60_000.0);
        this.privateChat = new Bucket(properties.burst(), properties.privateChatPerSecond() / 1000.0);
    }

    @Override
    public Duration tryAcquire(long chatId) {
        Bucket chat = ChatIds.isPrivate(chatId) ? privateChat : groupChat;
        long now = clock.millis();
        try {
            return buckets.tryAcquire(List.of(
                    new TokenBuckets.Bucket(GLOBAL_KEY, global.capacity(), global.tokensPerMs()),
                    new TokenBuckets.Bucket(CHAT_KEY_PREFIX + chatId, chat.capacity(), chat.tokensPerMs())), now, KEEP);
        } catch (DataAccessException e) {
            long last = lastWarning.get();
            if (now - last > WARN_EVERY_MS && lastWarning.compareAndSet(last, now)) {
                log.warn("Rate limiter unavailable (Redis): sending without local throttling: {}", e.getMessage());
            }
            return Duration.ZERO;
        }
    }

    record Bucket(int capacity, double tokensPerMs) {
    }
}
