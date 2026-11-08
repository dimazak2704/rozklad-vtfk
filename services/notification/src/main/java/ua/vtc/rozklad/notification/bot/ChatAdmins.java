package ua.vtc.rozklad.notification.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.config.BotProperties;
import ua.vtc.rozklad.notification.shared.ChatIds;
import ua.vtc.rozklad.notification.telegram.TelegramApi;
import ua.vtc.rozklad.notification.telegram.TelegramApi.MemberStatus;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/**
 * «Is the author of this group message a chat administrator?» — asked before a chat setting is switched.
 * An anonymous admin writes as the group itself ({@code sender_chat} = the chat) and is an admin by
 * definition; everyone else is checked with {@code getChatMember}. The answer is kept in Redis for
 * {@code app.bot.admin-check-ttl} (1 min, shared by instances), so a demoted admin keeps the right at most
 * that long. Without Redis every check asks Telegram (logged), the answer itself never changes.
 */
@Component
public class ChatAdmins {

    static final String KEY_PREFIX = "rozklad:notification:chat-admin:";

    private static final Logger log = LoggerFactory.getLogger(ChatAdmins.class);

    private final TelegramApi telegram;
    private final StringRedisTemplate redis;
    private final BotProperties bot;

    public ChatAdmins(TelegramApi telegram, StringRedisTemplate redis, BotProperties bot) {
        this.telegram = telegram;
        this.redis = redis;
        this.bot = bot;
    }

    /** @throws TelegramApiFailure when Telegram cannot answer (nothing is cached then) */
    public boolean isAdmin(ChatMessage message) {
        if (message.fromAnonymousAdmin()) {
            return true;
        }
        long chatId = message.chat().id();
        String key = KEY_PREFIX + chatId + ":" + message.fromUserId();
        String cached = read(key);
        if (cached != null) {
            return "1".equals(cached);
        }
        MemberStatus status = telegram.memberStatus(chatId, message.fromUserId());
        write(key, status.isAdmin() ? "1" : "0");
        log.debug("Member status in chat {}: {}", ChatIds.mask(chatId), status);
        return status.isAdmin();
    }

    private String read(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Admin check cache unavailable (Redis), asking Telegram: {}", e.getMessage());
            return null;
        }
    }

    private void write(String key, String value) {
        try {
            redis.opsForValue().set(key, value, bot.adminCheckTtl());
        } catch (DataAccessException e) {
            log.warn("Admin check cache unavailable (Redis), answer not cached: {}", e.getMessage());
        }
    }
}
