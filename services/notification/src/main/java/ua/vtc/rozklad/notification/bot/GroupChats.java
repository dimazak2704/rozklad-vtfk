package ua.vtc.rozklad.notification.bot;

import static ua.vtc.rozklad.notification.text.Html.b;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.shared.ChatIds;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/**
 * Group chats: only commands — {@code /bind <code>} (or {@code /start <code>} after
 * {@code ?startgroup=}), {@code /today}, {@code /tomorrow}, {@code /status}, {@code /rozklad_on},
 * {@code /rozklad_off}, {@code /help}. Ordinary chatter is ignored; commands addressed to other bots too.
 * Changes after an order are always delivered; only the 07:30 schedule is switched, and only by chat admins.
 */
@Component
public class GroupChats {

    static final String NOT_BOUND = "Цей чат ще не прив’язаний до групи. Надішліть сюди /bind КОД — код дає "
            + "диспетчер у веб-панелі.";
    static final String ADMINS_ONLY = "Змінити це може лише адміністратор чату.";

    private static final Logger log = LoggerFactory.getLogger(GroupChats.class);

    private final ChatAccess access;
    private final BotReplies replies;
    private final ScheduleViews views;
    private final BindingFlow binding;
    private final ChatBindings bindings;
    private final ChatAdmins admins;
    private final TelegramProperties telegram;

    public GroupChats(ChatAccess access, BotReplies replies, ScheduleViews views, BindingFlow binding,
                      ChatBindings bindings, ChatAdmins admins, TelegramProperties telegram) {
        this.access = access;
        this.replies = replies;
        this.views = views;
        this.binding = binding;
        this.bindings = bindings;
        this.admins = admins;
        this.telegram = telegram;
    }

    public void onMessage(ChatMessage message) {
        Optional<Command> parsed = Command.parse(message.text());
        if (parsed.isEmpty() || !parsed.get().addressedTo(telegram.botUsername())) {
            return;
        }
        Command command = parsed.get();
        long chatId = message.chat().id();
        if ((command.name().equals("bind") || command.name().equals("start")) && command.hasArgument()) {
            binding.redeem(message, command.argument());
            return;
        }
        if (!access.mayTalkTo(chatId)) {
            return;
        }
        switch (command.name()) {
            case "bind", "start" -> replies.send(chatId,
                    "Надішліть код прив’язки так: /bind КОД — код дає диспетчер у веб-панелі.", null);
            case "today" -> views.groupChatDay(chatId, 0);
            case "tomorrow" -> views.groupChatDay(chatId, 1);
            case "status" -> status(chatId);
            case "rozklad_on" -> switchDigest(message, true);
            case "rozklad_off" -> switchDigest(message, false);
            case "help" -> replies.send(chatId, BotTexts.groupHelp(), null);
            default -> {
                // інші команди в групі не наші — мовчимо, щоб не заважати розмові
            }
        }
    }

    private void status(long chatId) {
        Optional<ChatBinding> groupChat = bindings.groupChat(chatId);
        replies.send(chatId, groupChat.map(GroupChats::statusText).orElse(NOT_BOUND), null);
    }

    static String statusText(ChatBinding groupChat) {
        return "ℹ️ Чат прив’язано до групи " + b(groupChat.getGroupCode()) + ".\n"
                + "Цей чат отримує:\n"
                + "• заміни після публікації розпорядження ✅\n"
                + (groupChat.isDigestEnabled()
                ? "• ранковий розклад о 7:30 ✅ Вимкнути: /rozklad_off"
                : "• ранковий розклад о 7:30 — вимкнено. Увімкнути: /rozklad_on");
    }

    /** {@code /rozklad_on}, {@code /rozklad_off}: only chat admins; the binding is checked before asking Telegram. */
    private void switchDigest(ChatMessage message, boolean enabled) {
        long chatId = message.chat().id();
        if (bindings.groupChat(chatId).isEmpty()) {
            replies.send(chatId, NOT_BOUND, null);
            return;
        }
        boolean admin;
        try {
            admin = admins.isAdmin(message);
        } catch (TelegramApiFailure e) {
            log.warn("Admin check in chat {} failed: {}", ChatIds.mask(chatId), e.getMessage());
            replies.send(chatId, "Не вдалося перевірити, чи ви адміністратор чату. Спробуйте за хвилину.", null);
            return;
        }
        if (!admin) {
            replies.send(chatId, ADMINS_ONLY, null);
            return;
        }
        Optional<ChatBinding> updated = bindings.setGroupChatDigest(chatId, enabled);
        if (updated.isEmpty()) {
            // прив'язку видалили, поки ми питали Telegram
            replies.send(chatId, NOT_BOUND, null);
            return;
        }
        replies.send(chatId, enabled
                ? "✅ Ранковий розклад увімкнено: щоранку о 7:30 у навчальні дні тут буде розклад групи "
                + b(updated.get().getGroupCode()) + ". Вимкнути: /rozklad_off"
                : "Ранковий розклад вимкнено. Заміни після публікації розпорядження приходитимуть, як і раніше. "
                + "Увімкнути: /rozklad_on", null);
    }
}
