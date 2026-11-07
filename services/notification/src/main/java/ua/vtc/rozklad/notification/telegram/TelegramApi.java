package ua.vtc.rozklad.notification.telegram;

import java.util.List;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;

/**
 * The part of the Telegram Bot API the service uses. Texts are HTML ({@code parse_mode=HTML}),
 * dynamic parts must already be escaped. Every method throws {@link TelegramApiFailure} when
 * Telegram rejects the call or cannot be reached.
 */
public interface TelegramApi {

    BotIdentity getMe();

    SentMessage sendText(long chatId, String html, ReplyMarkup markup);

    SentMessage sendPhoto(long chatId, byte[] png, String fileName, String captionHtml);

    /** Replaces text and inline keyboard of a sent message; {@code keyboard == null} removes the buttons. */
    void editText(long chatId, long messageId, String html, InlineKeyboard keyboard);

    void answerCallback(String callbackQueryId, String text, boolean alert);

    void answerInlineQuery(String inlineQueryId, List<InlineArticle> articles, int cacheSeconds);

    void setCommands(CommandScope scope, List<BotCommandSpec> commands);

    void deleteWebhook();

    void setWebhook(String url, String secretToken, List<String> allowedUpdates);

    /** {@code getMe} result. */
    record BotIdentity(long id, String username, String firstName) {
    }

    record SentMessage(long chatId, long messageId) {
    }

    /** Inline-mode result: a card the user can send to any chat. */
    record InlineArticle(String id, String title, String description, String html) {
    }

    record BotCommandSpec(String command, String description) {
    }

    enum CommandScope {
        PRIVATE_CHATS,
        GROUP_CHATS
    }
}
