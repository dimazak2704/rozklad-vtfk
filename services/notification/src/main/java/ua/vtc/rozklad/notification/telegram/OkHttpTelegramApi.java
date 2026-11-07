package ua.vtc.rozklad.notification.telegram;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.AnswerInlineQuery;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScope;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllGroupChats;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllPrivateChats;
import org.telegram.telegrambots.meta.api.objects.inlinequery.inputmessagecontent.InputTextMessageContent;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResult;
import org.telegram.telegrambots.meta.api.objects.inlinequery.result.InlineQueryResultArticle;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiValidationException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * {@link TelegramApi} on top of {@code telegrambots-client} 10.x. The only class that knows the
 * library's method objects. Error messages never contain the bot token (it is part of the URL).
 */
public class OkHttpTelegramApi implements TelegramApi {

    private static final LinkPreviewOptions NO_PREVIEW = LinkPreviewOptions.builder().isDisabled(true).build();

    private final TelegramClient client;
    private final String botToken;

    public OkHttpTelegramApi(TelegramClient client, String botToken) {
        this.client = client;
        this.botToken = botToken;
    }

    @Override
    public BotIdentity getMe() {
        User me = call(() -> client.execute(new GetMe()));
        return new BotIdentity(me.getId(), me.getUserName(), me.getFirstName());
    }

    @Override
    public SentMessage sendText(long chatId, String html, ReplyMarkup markup) {
        SendMessage.SendMessageBuilder<?, ?> builder = SendMessage.builder()
                .chatId(Long.toString(chatId))
                .text(html)
                .parseMode(ParseMode.HTML)
                .linkPreviewOptions(NO_PREVIEW);
        ReplyKeyboard keyboard = toTelegram(markup);
        if (keyboard != null) {
            builder.replyMarkup(keyboard);
        }
        SendMessage method = builder.build();
        Message message = call(() -> client.execute(method));
        return new SentMessage(chatId, message.getMessageId());
    }

    @Override
    public SentMessage sendPhoto(long chatId, byte[] png, String fileName, String captionHtml) {
        SendPhoto.SendPhotoBuilder<?, ?> builder = SendPhoto.builder()
                .chatId(Long.toString(chatId))
                .photo(new InputFile(new ByteArrayInputStream(png), fileName));
        if (captionHtml != null && !captionHtml.isBlank()) {
            builder.caption(captionHtml).parseMode(ParseMode.HTML);
        }
        SendPhoto method = builder.build();
        Message message = call(() -> client.execute(method));
        return new SentMessage(chatId, message.getMessageId());
    }

    @Override
    public void editText(long chatId, long messageId, String html, ReplyMarkup.InlineKeyboard keyboard) {
        EditMessageText.EditMessageTextBuilder<?, ?> builder = EditMessageText.builder()
                .chatId(Long.toString(chatId))
                .messageId(Math.toIntExact(messageId))
                .text(html)
                .parseMode(ParseMode.HTML)
                .linkPreviewOptions(NO_PREVIEW);
        // без reply_markup Telegram прибирає кнопки з повідомлення — саме це потрібно для фінального стану
        if (keyboard != null && !keyboard.isEmpty()) {
            builder.replyMarkup(inlineKeyboard(keyboard));
        }
        EditMessageText method = builder.build();
        call(() -> client.execute(method));
    }

    @Override
    public void answerCallback(String callbackQueryId, String text, boolean alert) {
        AnswerCallbackQuery.AnswerCallbackQueryBuilder<?, ?> builder = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .showAlert(alert);
        if (text != null && !text.isBlank()) {
            builder.text(text);
        }
        AnswerCallbackQuery method = builder.build();
        call(() -> client.execute(method));
    }

    @Override
    public void answerInlineQuery(String inlineQueryId, List<InlineArticle> articles, int cacheSeconds) {
        List<InlineQueryResult> results = articles.stream()
                .<InlineQueryResult>map(article -> InlineQueryResultArticle.builder()
                        .id(article.id())
                        .title(article.title())
                        .description(article.description())
                        .inputMessageContent(InputTextMessageContent.builder()
                                .messageText(article.html())
                                .parseMode(ParseMode.HTML)
                                .linkPreviewOptions(NO_PREVIEW)
                                .build())
                        .build())
                .toList();
        AnswerInlineQuery method = AnswerInlineQuery.builder()
                .inlineQueryId(inlineQueryId)
                .results(results)
                .cacheTime(cacheSeconds)
                .isPersonal(false)
                .build();
        call(() -> client.execute(method));
    }

    @Override
    public void setCommands(CommandScope scope, List<BotCommandSpec> commands) {
        BotCommandScope telegramScope = switch (scope) {
            case PRIVATE_CHATS -> BotCommandScopeAllPrivateChats.builder().build();
            case GROUP_CHATS -> BotCommandScopeAllGroupChats.builder().build();
        };
        SetMyCommands method = SetMyCommands.builder()
                .commands(commands.stream().map(c -> new BotCommand(c.command(), c.description())).toList())
                .scope(telegramScope)
                .build();
        call(() -> client.execute(method));
    }

    @Override
    public void deleteWebhook() {
        DeleteWebhook method = DeleteWebhook.builder().build();
        call(() -> client.execute(method));
    }

    @Override
    public void setWebhook(String url, String secretToken, List<String> allowedUpdates) {
        SetWebhook.SetWebhookBuilder<?, ?> builder = SetWebhook.builder().url(url).allowedUpdates(allowedUpdates);
        if (secretToken != null && !secretToken.isBlank()) {
            builder.secretToken(secretToken);
        }
        SetWebhook method = builder.build();
        call(() -> client.execute(method));
    }

    static ReplyKeyboard toTelegram(ReplyMarkup markup) {
        return switch (markup) {
            case null -> null;
            case ReplyMarkup.InlineKeyboard inline -> inline.isEmpty() ? null : inlineKeyboard(inline);
            case ReplyMarkup.ReplyKeyboard reply -> ReplyKeyboardMarkup.builder()
                    .keyboard(reply.rows().stream().map(row -> new KeyboardRow(row.toArray(String[]::new))).toList())
                    .resizeKeyboard(true)
                    .isPersistent(true)
                    .inputFieldPlaceholder(reply.placeholder())
                    .build();
            case ReplyMarkup.RemoveKeyboard ignored -> new ReplyKeyboardRemove(true);
        };
    }

    static InlineKeyboardMarkup inlineKeyboard(ReplyMarkup.InlineKeyboard keyboard) {
        List<InlineKeyboardRow> rows = keyboard.rows().stream()
                .map(row -> new InlineKeyboardRow(row.stream().map(OkHttpTelegramApi::button).toList()))
                .toList();
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static InlineKeyboardButton button(ReplyMarkup.InlineButton button) {
        InlineKeyboardButton.InlineKeyboardButtonBuilder<?, ?> builder = InlineKeyboardButton.builder()
                .text(button.text());
        if (button.callbackData() != null) {
            builder.callbackData(button.callbackData());
        } else if (button.url() != null) {
            builder.url(button.url());
        } else {
            builder.switchInlineQueryCurrentChat(button.switchInlineQueryCurrentChat());
        }
        return builder.build();
    }

    private <T> T call(TelegramCall<T> call) {
        try {
            return call.execute();
        } catch (TelegramApiRequestException e) {
            ResponseParameters parameters = e.getParameters();
            String description = e.getApiResponse() != null ? e.getApiResponse() : e.getMessage();
            throw new TelegramApiFailure(e.getErrorCode(), redact(description),
                    parameters == null ? null : parameters.getRetryAfter(),
                    parameters == null ? null : parameters.getMigrateToChatId(), null);
        } catch (TelegramApiValidationException e) {
            // помилка формування запиту на нашому боці: повтор не допоможе
            throw new TelegramApiFailure(400, "Invalid request: " + redact(e.getMessage()), null, null, null);
        } catch (TelegramApiException e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            throw TelegramApiFailure.network(root.getClass().getSimpleName() + ": " + redact(root.getMessage()), null);
        }
    }

    /** OkHttp messages may contain the request URL, and the URL contains the token. */
    private String redact(String text) {
        if (text == null || botToken == null || botToken.isBlank()) {
            return text;
        }
        return text.replace(botToken, "***");
    }

    @FunctionalInterface
    private interface TelegramCall<T> {
        T execute() throws TelegramApiException;
    }
}
