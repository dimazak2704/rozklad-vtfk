package ua.vtc.rozklad.notification.telegram;

import java.util.List;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;

/** Used when {@code TELEGRAM_BOT_TOKEN} is empty: every call fails loudly instead of pretending to send. */
public class DisabledTelegramApi implements TelegramApi {

    @Override
    public BotIdentity getMe() {
        throw TelegramApiFailure.botDisabled();
    }

    @Override
    public SentMessage sendText(long chatId, String html, ReplyMarkup markup) {
        throw TelegramApiFailure.botDisabled();
    }

    @Override
    public SentMessage sendPhoto(long chatId, byte[] png, String fileName, String captionHtml) {
        throw TelegramApiFailure.botDisabled();
    }

    @Override
    public void editText(long chatId, long messageId, String html, InlineKeyboard keyboard) {
        throw TelegramApiFailure.botDisabled();
    }

    @Override
    public void answerCallback(String callbackQueryId, String text, boolean alert) {
        throw TelegramApiFailure.botDisabled();
    }

    @Override
    public void answerInlineQuery(String inlineQueryId, List<InlineArticle> articles, int cacheSeconds) {
        throw TelegramApiFailure.botDisabled();
    }

    @Override
    public void setCommands(CommandScope scope, List<BotCommandSpec> commands) {
        throw TelegramApiFailure.botDisabled();
    }

    @Override
    public MemberStatus memberStatus(long chatId, long userId) {
        throw TelegramApiFailure.botDisabled();
    }

    @Override
    public void deleteWebhook() {
        throw TelegramApiFailure.botDisabled();
    }

    @Override
    public void setWebhook(String url, String secretToken, List<String> allowedUpdates) {
        throw TelegramApiFailure.botDisabled();
    }
}
