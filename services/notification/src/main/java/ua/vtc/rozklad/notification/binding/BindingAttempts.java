package ua.vtc.rozklad.notification.binding;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import ua.vtc.rozklad.notification.config.BotProperties;
import ua.vtc.rozklad.notification.shared.ChatIds;

/**
 * Limit on guessing binding codes: a chat (and the Telegram user behind it) may send at most
 * {@code app.bot.bind-attempts} (5) wrong codes per {@code app.bot.bind-attempts-window} (15 min); after that
 * codes are not even looked up until the window ends. Counters live in Redis (shared by instances); the window
 * starts with the first failure. Without Redis no code is checked at all (fail closed): binding is rare and
 * must not become unlimited guessing during an outage.
 */
@Service
public class BindingAttempts {

    static final String CHAT_KEY_PREFIX = "rozklad:notification:bind-fail:chat:";
    static final String USER_KEY_PREFIX = "rozklad:notification:bind-fail:user:";

    /** KEYS: counters; ARGV[1] = window (ms). Increments each counter, starting its window on the first failure. */
    private static final RedisScript<Long> RECORD = RedisScript.of("""
            for i = 1, #KEYS do
              if redis.call('INCR', KEYS[i]) == 1 then
                redis.call('PEXPIRE', KEYS[i], ARGV[1])
              end
            end
            return #KEYS
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(BindingAttempts.class);

    private final StringRedisTemplate redis;
    private final BotProperties bot;

    public BindingAttempts(StringRedisTemplate redis, BotProperties bot) {
        this.redis = redis;
        this.bot = bot;
    }

    /** May this chat/user try a code now? {@code userId == null} — only the chat counts (anonymous admin). */
    public Check check(long chatId, Long userId) {
        try {
            List<String> counts = redis.opsForValue().multiGet(keys(chatId, userId));
            boolean blocked = counts != null && counts.stream()
                    .anyMatch(count -> count != null && Long.parseLong(count) >= bot.bindAttempts());
            if (blocked) {
                log.warn("Binding code attempts of chat {} blocked: {} failures within {}", ChatIds.mask(chatId),
                        bot.bindAttempts(), bot.bindAttemptsWindow());
            }
            return blocked ? Check.BLOCKED : Check.ALLOWED;
        } catch (DataAccessException e) {
            log.error("Binding code attempts cannot be counted (Redis): code of chat {} not checked: {}",
                    ChatIds.mask(chatId), e.getMessage());
            return Check.UNAVAILABLE;
        }
    }

    /** A code was rejected (unknown, used, expired, wrong chat). */
    public void recordFailure(long chatId, Long userId) {
        try {
            redis.execute(RECORD, keys(chatId, userId), Long.toString(bot.bindAttemptsWindow().toMillis()));
        } catch (DataAccessException e) {
            log.error("Failed binding attempt of chat {} not counted (Redis): {}", ChatIds.mask(chatId),
                    e.getMessage());
        }
    }

    private static List<String> keys(long chatId, Long userId) {
        List<String> keys = new ArrayList<>(2);
        keys.add(CHAT_KEY_PREFIX + chatId);
        if (userId != null && userId != chatId) {
            keys.add(USER_KEY_PREFIX + userId);
        }
        return keys;
    }

    public enum Check {
        ALLOWED,
        /** Too many wrong codes recently: the code is not looked up. */
        BLOCKED,
        /** Redis is down: the code is not looked up (fail closed). */
        UNAVAILABLE
    }
}
