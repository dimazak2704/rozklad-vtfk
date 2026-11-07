package ua.vtc.rozklad.notification.bot;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.delivery.RateLimiter;
import ua.vtc.rozklad.notification.shared.ChatIds;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.telegram.TelegramApi;
import ua.vtc.rozklad.notification.telegram.TelegramApi.InlineArticle;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;
import ua.vtc.rozklad.notification.text.Html;

/**
 * Immediate answers to a user's action (pushes go through the outbound queue instead). Takes the
 * same rate-limit tokens as the queue, splits long texts, waits once on 429. A failed reply is
 * logged: the user simply sees no answer and can repeat the action.
 */
@Component
public class BotReplies {

    private static final Logger log = LoggerFactory.getLogger(BotReplies.class);
    private static final Duration MAX_WAIT = Duration.ofSeconds(3);
    private static final int MAX_RETRY_AFTER_SECONDS = 10;
    private static final int INLINE_CACHE_SECONDS = 60;

    private final TelegramApi telegram;
    private final RateLimiter limiter;

    public BotReplies(TelegramApi telegram, RateLimiter limiter) {
        this.telegram = telegram;
        this.limiter = limiter;
    }

    /** @return id of the (last) sent message */
    public Optional<Long> send(long chatId, String html, ReplyMarkup markup) {
        List<String> parts = Html.split(html, Html.MAX_MESSAGE);
        Optional<Long> last = Optional.empty();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            ReplyMarkup partMarkup = i == parts.size() - 1 ? markup : null;
            last = call(chatId, () -> telegram.sendText(chatId, part, partMarkup).messageId());
        }
        return last;
    }

    /**
     * Shows a new state in an existing message (wizards, navigation). If the message cannot be
     * edited (too old, deleted, text too long), the state is sent as a new message.
     */
    public void edit(long chatId, long messageId, String html, InlineKeyboard keyboard) {
        if (html.length() > Html.MAX_MESSAGE) {
            send(chatId, html, keyboard);
            return;
        }
        throttle(chatId);
        try {
            telegram.editText(chatId, messageId, html, keyboard);
        } catch (TelegramApiFailure failure) {
            if (failure.isMessageNotModified()) {
                return;
            }
            log.debug("Edit in chat {} failed ({}), sending a new message", ChatIds.mask(chatId), failure.getMessage());
            send(chatId, html, keyboard);
        }
    }

    public void answer(String callbackId) {
        answer(callbackId, null, false);
    }

    public void answer(String callbackId, String text) {
        answer(callbackId, text, false);
    }

    public void alert(String callbackId, String text) {
        answer(callbackId, text, true);
    }

    public void answerInline(String queryId, List<InlineArticle> articles) {
        try {
            telegram.answerInlineQuery(queryId, articles, INLINE_CACHE_SECONDS);
        } catch (TelegramApiFailure failure) {
            log.warn("Inline answer failed: {}", failure.getMessage());
        }
    }

    private void answer(String callbackId, String text, boolean alert) {
        try {
            telegram.answerCallback(callbackId, text, alert);
        } catch (TelegramApiFailure failure) {
            // запит міг застаріти (Telegram чекає відповідь ~15 с) — на дію користувача це не впливає
            log.debug("Callback answer failed: {}", failure.getMessage());
        }
    }

    private <T> Optional<T> call(long chatId, Supplier<T> call) {
        throttle(chatId);
        try {
            return Optional.ofNullable(call.get());
        } catch (TelegramApiFailure failure) {
            if (failure.isRateLimited() && failure.retryAfterSeconds() != null
                    && failure.retryAfterSeconds() <= MAX_RETRY_AFTER_SECONDS) {
                sleep(Duration.ofSeconds(failure.retryAfterSeconds()));
                try {
                    return Optional.ofNullable(call.get());
                } catch (TelegramApiFailure again) {
                    failure = again;
                }
            }
            log.warn("Reply to chat {} failed: {}", ChatIds.mask(chatId), failure.getMessage());
            return Optional.empty();
        }
    }

    private void throttle(long chatId) {
        Duration wait = limiter.tryAcquire(chatId);
        if (!wait.isZero() && wait.compareTo(MAX_WAIT) <= 0) {
            sleep(wait);
            limiter.tryAcquire(chatId);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
