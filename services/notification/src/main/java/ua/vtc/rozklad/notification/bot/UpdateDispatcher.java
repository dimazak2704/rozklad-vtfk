package ua.vtc.rozklad.notification.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.bot.BotUpdate.CallbackAction;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMigration;
import ua.vtc.rozklad.notification.bot.BotUpdate.InlineRequest;
import ua.vtc.rozklad.notification.bot.BotUpdate.MembershipChange;
import ua.vtc.rozklad.notification.web.RequestIdFilter;

/**
 * Entry point of every update (long polling or webhook). Handles one update synchronously; the
 * caller decides about threading. An unexpected failure is logged and the user gets a short apology.
 */
@Component
public class UpdateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UpdateDispatcher.class);

    private final PrivateChats privateChats;
    private final GroupChats groupChats;
    private final CallbackRouter callbacks;
    private final InlineQueries inline;
    private final ChatBindings bindings;
    private final ChatAccess access;
    private final BotReplies replies;

    public UpdateDispatcher(PrivateChats privateChats, GroupChats groupChats, CallbackRouter callbacks,
                            InlineQueries inline, ChatBindings bindings, ChatAccess access, BotReplies replies) {
        this.privateChats = privateChats;
        this.groupChats = groupChats;
        this.callbacks = callbacks;
        this.inline = inline;
        this.bindings = bindings;
        this.access = access;
        this.replies = replies;
    }

    public void dispatch(BotUpdate update) {
        // ідентифікатор запиту в логах і в X-Request-Id викликів core
        MDC.put(RequestIdFilter.MDC_KEY, "tg-" + update.updateId());
        try {
            switch (update) {
                case ChatMessage message when message.chat().isPrivate() -> privateChats.onMessage(message);
                case ChatMessage message when message.chat().isGroup() -> groupChats.onMessage(message);
                case ChatMessage ignored -> log.debug("Channel post ignored");
                case CallbackAction action -> callbacks.onCallback(action);
                case InlineRequest request -> inline.onQuery(request);
                case MembershipChange change -> onMembership(change);
                case ChatMigration migration -> bindings.migrateChat(migration.fromChatId(), migration.toChatId());
            }
        } catch (RuntimeException e) {
            log.error("Telegram update {} failed", update.updateId(), e);
            apologize(update);
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    private void onMembership(MembershipChange change) {
        long chatId = change.chat().id();
        if (!change.botIsMember()) {
            bindings.forgetChat(chatId);
            return;
        }
        if (change.chat().isGroup() && access.mayTalkTo(chatId)) {
            replies.send(chatId, "Вітаю! Я бот розкладу ВТФК 👋\nЩоб цей чат отримував заміни своєї групи, "
                    + "надішліть сюди /bind КОД — код дає диспетчер у веб-панелі.", null);
        }
    }

    private void apologize(BotUpdate update) {
        try {
            switch (update) {
                case ChatMessage message when access.mayTalkTo(message.chat().id()) ->
                        replies.send(message.chat().id(), BotTexts.SOMETHING_WRONG, null);
                case CallbackAction action -> replies.alert(action.callbackId(), "Щось пішло не так. Спробуйте ще раз.");
                default -> {
                    // для інших оновлень користувачеві нема куди відповісти
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not apologize for update {}: {}", update.updateId(), e.getMessage());
        }
    }
}
