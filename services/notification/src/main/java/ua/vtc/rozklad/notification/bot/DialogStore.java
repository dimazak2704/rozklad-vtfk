package ua.vtc.rozklad.notification.bot;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.notification.config.BotProperties;

/** Dialog state per chat in Redis (JSON, expires after {@code app.bot.dialog-ttl} of inactivity). */
@Component
public class DialogStore {

    static final String PREFIX = "rozklad:notification:dialog:";

    private static final Logger log = LoggerFactory.getLogger(DialogStore.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final BotProperties properties;

    public DialogStore(StringRedisTemplate redis, JsonMapper json, BotProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    public Optional<Dialog> get(long chatId) {
        try {
            String value = redis.opsForValue().get(PREFIX + chatId);
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, Dialog.class));
        } catch (DataAccessException e) {
            log.warn("Dialog state unavailable (Redis): {}", e.getMessage());
            return Optional.empty();
        } catch (JacksonException e) {
            log.warn("Dropping unreadable dialog state: {}", e.getOriginalMessage());
            clear(chatId);
            return Optional.empty();
        }
    }

    public void save(long chatId, Dialog dialog) {
        try {
            redis.opsForValue().set(PREFIX + chatId, json.writeValueAsString(dialog), properties.dialogTtl());
        } catch (DataAccessException e) {
            log.warn("Dialog state not saved (Redis): {}", e.getMessage());
        }
    }

    public void clear(long chatId) {
        try {
            redis.delete(PREFIX + chatId);
        } catch (DataAccessException e) {
            log.warn("Dialog state not cleared (Redis): {}", e.getMessage());
        }
    }
}
