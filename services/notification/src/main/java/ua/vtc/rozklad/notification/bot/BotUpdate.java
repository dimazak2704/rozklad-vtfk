package ua.vtc.rozklad.notification.bot;

import java.util.Objects;

/**
 * An incoming Telegram update reduced to what the bot handles. Built from the library's
 * {@code Update} by {@code UpdateMapper}; tests create these records directly.
 */
public sealed interface BotUpdate permits BotUpdate.ChatMessage, BotUpdate.CallbackAction, BotUpdate.InlineRequest,
        BotUpdate.MembershipChange, BotUpdate.ChatMigration {

    long updateId();

    /** Serialization key: updates with the same key are handled one after another. */
    long orderingKey();

    enum ChatKind {
        PRIVATE,
        GROUP,
        CHANNEL
    }

    record ChatRef(long id, ChatKind kind, String title) {

        public ChatRef {
            Objects.requireNonNull(kind, "kind");
        }

        public boolean isPrivate() {
            return kind == ChatKind.PRIVATE;
        }

        public boolean isGroup() {
            return kind == ChatKind.GROUP;
        }
    }

    /**
     * A text message (commands included). {@code senderChatId} is set when the message was sent on behalf
     * of a chat ({@code sender_chat}): an anonymous group administrator writes as the group itself.
     */
    record ChatMessage(long updateId, ChatRef chat, long fromUserId, long messageId, String text, Long senderChatId)
            implements BotUpdate {

        public ChatMessage {
            Objects.requireNonNull(chat, "chat");
            Objects.requireNonNull(text, "text");
        }

        public ChatMessage(long updateId, ChatRef chat, long fromUserId, long messageId, String text) {
            this(updateId, chat, fromUserId, messageId, text, null);
        }

        /** Sent by an anonymous administrator of this very group (only admins can stay anonymous). */
        public boolean fromAnonymousAdmin() {
            return senderChatId != null && senderChatId == chat.id();
        }

        @Override
        public long orderingKey() {
            return chat.id();
        }
    }

    /** A pressed inline button. {@code chat}/{@code messageId} are null for inline-mode messages. */
    record CallbackAction(long updateId, String callbackId, long fromUserId, ChatRef chat, Long messageId,
                          String data) implements BotUpdate {

        public CallbackAction {
            Objects.requireNonNull(callbackId, "callbackId");
            data = data == null ? "" : data;
        }

        @Override
        public long orderingKey() {
            return chat != null ? chat.id() : fromUserId;
        }
    }

    /** {@code @scheduler_vtfk_bot 4КІ1} typed in any chat. */
    record InlineRequest(long updateId, String queryId, long fromUserId, String query) implements BotUpdate {

        public InlineRequest {
            Objects.requireNonNull(queryId, "queryId");
            query = query == null ? "" : query;
        }

        @Override
        public long orderingKey() {
            return fromUserId;
        }
    }

    /** The bot was added to or removed from a chat. */
    record MembershipChange(long updateId, ChatRef chat, boolean botIsMember) implements BotUpdate {

        @Override
        public long orderingKey() {
            return chat.id();
        }
    }

    /** A group was upgraded to a supergroup and got a new id. */
    record ChatMigration(long updateId, long fromChatId, long toChatId) implements BotUpdate {

        @Override
        public long orderingKey() {
            return fromChatId;
        }
    }
}
