package ua.vtc.rozklad.notification.shared;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token buckets in Redis, shared by all instances: one Lua script checks every bucket of a call and takes a
 * token from each only if all of them have one. Used by the Telegram limiter and the e-mail rate guard.
 */
@Component
public class TokenBuckets {

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
              redis.call('PEXPIRE', KEYS[i], ARGV[2 * #KEYS + 2])
            end
            return wait
            """, Long.class);

    private final StringRedisTemplate redis;

    public TokenBuckets(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Takes one token from every bucket, or none.
     *
     * @param nowMillis the caller's clock
     * @param keep      how long an idle bucket stays in Redis (longer than its refill time)
     * @return {@link Duration#ZERO} when the tokens were taken, otherwise how long to wait
     * @throws DataAccessException when Redis is unavailable (the caller decides what that means)
     */
    public Duration tryAcquire(List<Bucket> buckets, long nowMillis, Duration keep) {
        List<String> keys = new ArrayList<>(buckets.size());
        List<String> args = new ArrayList<>(2 * buckets.size() + 2);
        args.add(Long.toString(nowMillis));
        for (Bucket bucket : buckets) {
            keys.add(bucket.key());
            args.add(Integer.toString(bucket.capacity()));
            args.add(Double.toString(bucket.tokensPerMs()));
        }
        args.add(Long.toString(keep.toMillis()));
        Long waitMs = redis.execute(SCRIPT, keys, args.toArray());
        return waitMs == null || waitMs <= 0 ? Duration.ZERO : Duration.ofMillis(waitMs);
    }

    /** One bucket: its Redis key, capacity (burst) and refill rate. */
    public record Bucket(String key, int capacity, double tokensPerMs) {
    }
}
