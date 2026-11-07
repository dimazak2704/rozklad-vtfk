package ua.vtc.rozklad.notification.delivery;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.DeliveryProperties;
import ua.vtc.rozklad.notification.shared.ChatIds;

/**
 * Token buckets in Redis (shared by all instances), checked and taken atomically by a Lua script:
 * global ~25 msg/s, a group chat ~18 msg/min, a private chat ~1 msg/s, each chat with a small burst.
 * If Redis is down, sending is allowed (logged): Telegram's own 429 then throttles us via
 * {@link RetryPolicy}.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    static final String GLOBAL_KEY = "rozklad:notification:ratelimit:global";
    static final String CHAT_KEY_PREFIX = "rozklad:notification:ratelimit:chat:";

    /**
     * KEYS: buckets; ARGV[1] = now (ms), then capacity and refill rate (tokens/ms) per bucket.
     * Returns 0 when one token was taken from every bucket, otherwise the wait in ms.
     */
    private static final RedisScript<Long> SCRIPT = RedisScript.of("""
            local now = tonumber(ARGV[1])
            local wait = 0
            local tokens = {}
            local stamps = {}
            for i = 1, #KEYS do
              local capacity = tonumber(ARGV[2 * i])
              local rate = tonumber(ARGV[2 * i + 1])
              local state = redis.call('HMGET', KEYS[i], 'tokens', 'ts')
              local t = tonumber(state[1])
              local ts = tonumber(state[2])
              if t == nil or ts == nil then
                t = capacity
                ts = now
              end
              if now > ts then
                t = math.min(capacity, t + (now - ts) * rate)
                ts = now
              end
              tokens[i] = t
              stamps[i] = ts
              if t < 1 then
                local w = math.ceil((1 - t) / rate)
                if w > wait then wait = w end
              end
            end
            if wait == 0 then
              for i = 1, #KEYS do tokens[i] = tokens[i] - 1 end
            end
            for i = 1, #KEYS do
              redis.call('HSET', KEYS[i], 'tokens', tostring(tokens[i]), 'ts', tostring(stamps[i]))
              redis.call('PEXPIRE', KEYS[i], 3600000)
            end
            return wait
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final long WARN_EVERY_MS = 60_000;

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Bucket global;
    private final Bucket groupChat;
    private final Bucket privateChat;
    private final AtomicLong lastWarning = new AtomicLong();

    public RedisRateLimiter(StringRedisTemplate redis, Clock clock, DeliveryProperties properties) {
        this.redis = redis;
        this.clock = clock;
        this.global = new Bucket(properties.globalPerSecond(), properties.globalPerSecond() / 1000.0);
        this.groupChat = new Bucket(properties.burst(), properties.groupChatPerMinute() / 60_000.0);
        this.privateChat = new Bucket(properties.burst(), properties.privateChatPerSecond() / 1000.0);
    }

    @Override
    public Duration tryAcquire(long chatId) {
        Bucket chat = ChatIds.isPrivate(chatId) ? privateChat : groupChat;
        long now = clock.millis();
        try {
            Long waitMs = redis.execute(SCRIPT, List.of(GLOBAL_KEY, CHAT_KEY_PREFIX + chatId),
                    Long.toString(now),
                    Integer.toString(global.capacity()), Double.toString(global.tokensPerMs()),
                    Integer.toString(chat.capacity()), Double.toString(chat.tokensPerMs()));
            return waitMs == null || waitMs <= 0 ? Duration.ZERO : Duration.ofMillis(waitMs);
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
