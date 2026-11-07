package ua.vtc.rozklad.notification.bot;

import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.config.TelegramProperties;

/**
 * Who the bot talks to. BOUND_ONLY (default): the owner chat and chats bound through the system;
 * other chats get an answer only when they present a real binding code. OPEN: everyone.
 */
@Component
public class ChatAccess {

    private final TelegramProperties telegram;
    private final ChatBindings bindings;

    public ChatAccess(TelegramProperties telegram, ChatBindings bindings) {
        this.telegram = telegram;
        this.bindings = bindings;
    }

    public boolean mayTalkTo(long chatId) {
        return telegram.access() == TelegramProperties.Access.OPEN || bindings.isKnownChat(chatId);
    }
}
