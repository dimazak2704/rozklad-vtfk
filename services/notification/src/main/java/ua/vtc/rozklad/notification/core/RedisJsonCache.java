package ua.vtc.rozklad.notification.core;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-through JSON cache in Redis. A Redis outage is logged and the value is loaded from the
 * source of truth (core) instead — the cache never invents data.
 */
@Component
public class RedisJsonCache {

    static final String PREFIX = "rozklad:notification:cache:";

    private static final Logger log = LoggerFactory.getLogger(RedisJsonCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RedisJsonCache(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public <T> T get(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        String fullKey = PREFIX + key;
        String cached = read(fullKey);
        if (cached != null) {
            try {
                return json.readValue(cached, type);
            } catch (JacksonException e) {
                log.warn("Dropping unreadable cache entry {}: {}", key, e.getOriginalMessage());
            }
        }
        T value = loader.get();
        if (value != null) {
            write(fullKey, json.writeValueAsString(value), ttl);
        }
        return value;
    }

    /** Current value of a counter (0 when absent or Redis is down). */
    public long counter(String key) {
        String value = read(PREFIX + key);
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void increment(String key) {
        try {
            redis.opsForValue().increment(PREFIX + key);
        } catch (DataAccessException e) {
            log.warn("Redis is unavailable, counter {} not incremented: {}", key, e.getMessage());
        }
    }

    public void evict(String key) {
        try {
            redis.delete(PREFIX + key);
        } catch (DataAccessException e) {
            log.warn("Redis is unavailable, cache entry {} not evicted: {}", key, e.getMessage());
        }
    }

    private String read(String fullKey) {
        try {
            return redis.opsForValue().get(fullKey);
        } catch (DataAccessException e) {
            log.warn("Redis is unavailable, reading {} from the source: {}", fullKey, e.getMessage());
            return null;
        }
    }

    private void write(String fullKey, String value, Duration ttl) {
        try {
            redis.opsForValue().set(fullKey, value, ttl);
        } catch (DataAccessException e) {
            log.warn("Redis is unavailable, {} not cached: {}", fullKey, e.getMessage());
        }
    }
}
