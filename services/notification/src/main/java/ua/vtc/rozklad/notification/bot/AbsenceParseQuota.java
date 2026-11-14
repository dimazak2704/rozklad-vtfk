package ua.vtc.rozklad.notification.bot;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.config.BotProperties;

/**
 * How many free-text absences of one teacher go to the importer's parser (which may call the paid text model):
 * {@code app.bot.absence-parses-per-minute} (5) and {@code app.bot.absence-parses-per-day} (20, a rolling 24 h
 * from the first one). A teacher writes one or two such messages a day; a script in a bound chat would otherwise
 * spend the college's whole daily AI budget, and the admins' imports would stop until midnight. Over the limit the
 * wizard simply offers the date buttons. Counters live in Redis (shared by instances); without Redis the text is
 * not parsed (fail closed), the buttons still work.
 */
@Component
public class AbsenceParseQuota {

    static final String MINUTE_KEY_PREFIX = "rozklad:notification:absence-parse:minute:";
    static final String DAY_KEY_PREFIX = "rozklad:notification:absence-parse:day:";

    /**
     * KEYS: minute and day counters; ARGV: their windows (ms) and limits. Counts the call (a refused one too, so a
     * flood stays refused) and returns 1 when both counts are within their limits.
     */
    private static final RedisScript<Long> COUNT = RedisScript.of("""
            local minute = redis.call('INCR', KEYS[1])
            if minute == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            local day = redis.call('INCR', KEYS[2])
            if day == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[2]) end
            if minute > tonumber(ARGV[3]) or day > tonumber(ARGV[4]) then return 0 end
            return 1
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(AbsenceParseQuota.class);

    private final StringRedisTemplate redis;
    private final BotProperties bot;

    public AbsenceParseQuota(StringRedisTemplate redis, BotProperties bot) {
        this.redis = redis;
        this.bot = bot;
    }

    /** Counts one parse of {@code teacherId}'s text; {@code false} when it is over a limit (or Redis is down). */
    public boolean tryAcquire(UUID teacherId) {
        try {
            Long allowed = redis.execute(COUNT, List.of(MINUTE_KEY_PREFIX + teacherId, DAY_KEY_PREFIX + teacherId),
                    Long.toString(Duration.ofMinutes(1).toMillis()), Long.toString(Duration.ofDays(1).toMillis()),
                    Integer.toString(bot.absenceParsesPerMinute()), Integer.toString(bot.absenceParsesPerDay()));
            if (allowed == null || allowed != 1L) {
                log.warn("Free-text absence of teacher {} not parsed: over {} a minute or {} a day", teacherId,
                        bot.absenceParsesPerMinute(), bot.absenceParsesPerDay());
                return false;
            }
            return true;
        } catch (DataAccessException e) {
            log.error("Absence parses of teacher {} cannot be counted (Redis): text not parsed: {}", teacherId,
                    e.getMessage());
            return false;
        }
    }
}
