package ua.vtc.rozklad.notification.telegram;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.inlinequery.InlineQuery;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import ua.vtc.rozklad.notification.bot.BotUpdate;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatKind;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatRef;

/** Telegram library {@link Update} → {@link BotUpdate}; everything the bot does not handle is dropped. */
public final class UpdateMapper {

    /** Update types requested from Telegram (getUpdates / setWebhook). */
    public static final List<String> ALLOWED_UPDATES = List.of("message", "callback_query", "inline_query",
            "my_chat_member");

    private static final Set<String> MEMBER_STATUSES = Set.of("member", "administrator", "creator", "restricted");

    private UpdateMapper() {
    }

    public static Optional<BotUpdate> map(Update update) {
        long updateId = update.getUpdateId() == null ? 0 : update.getUpdateId();
        if (update.getMessage() != null) {
            return message(updateId, update.getMessage());
        }
        if (update.getCallbackQuery() != null) {
            return Optional.of(callback(updateId, update.getCallbackQuery()));
        }
        if (update.getInlineQuery() != null) {
            InlineQuery query = update.getInlineQuery();
            return Optional.of(new BotUpdate.InlineRequest(updateId, query.getId(), query.getFrom().getId(),
                    query.getQuery()));
        }
        if (update.getMyChatMember() != null) {
            return membership(updateId, update.getMyChatMember());
        }
        return Optional.empty();
    }

    private static Optional<BotUpdate> message(long updateId, Message message) {
        if (message.getMigrateToChatId() != null) {
            return Optional.of(new BotUpdate.ChatMigration(updateId, message.getChatId(),
                    message.getMigrateToChatId()));
        }
        if (message.getText() == null || message.getFrom() == null) {
            return Optional.empty();
        }
        Long senderChatId = message.getSenderChat() == null ? null : message.getSenderChat().getId();
        return Optional.of(new BotUpdate.ChatMessage(updateId, chat(message.getChat()), message.getFrom().getId(),
                message.getMessageId(), message.getText(), senderChatId));
    }

    private static BotUpdate callback(long updateId, CallbackQuery query) {
        MaybeInaccessibleMessage message = query.getMessage();
        ChatRef chat = message == null || message.getChat() == null ? null : chat(message.getChat());
        Long messageId = message == null || message.getMessageId() == null ? null : message.getMessageId().longValue();
        return new BotUpdate.CallbackAction(updateId, query.getId(), query.getFrom().getId(), chat, messageId,
                query.getData());
    }

    private static Optional<BotUpdate> membership(long updateId, ChatMemberUpdated change) {
        if (change.getNewChatMember() == null || change.getChat() == null) {
            return Optional.empty();
        }
        boolean member = MEMBER_STATUSES.contains(change.getNewChatMember().getStatus());
        return Optional.of(new BotUpdate.MembershipChange(updateId, chat(change.getChat()), member));
    }

    static ChatRef chat(Chat chat) {
        ChatKind kind = switch (chat.getType() == null ? "" : chat.getType()) {
            case "private" -> ChatKind.PRIVATE;
            case "group", "supergroup" -> ChatKind.GROUP;
            default -> ChatKind.CHANNEL;
        };
        String title = chat.getTitle() != null ? chat.getTitle() : chat.getFirstName();
        return new ChatRef(chat.getId(), kind, title);
    }
}
