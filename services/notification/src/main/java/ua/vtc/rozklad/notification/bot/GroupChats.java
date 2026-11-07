package ua.vtc.rozklad.notification.bot;

import java.util.Optional;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.config.TelegramProperties;

/**
 * Group chats: only commands — {@code /bind <code>} (or {@code /start <code>} after
 * {@code ?startgroup=}), {@code /today}, {@code /tomorrow}, {@code /help}. Ordinary chatter is
 * ignored; commands addressed to other bots too.
 */
@Component
public class GroupChats {

    private final ChatAccess access;
    private final BotReplies replies;
    private final ScheduleViews views;
    private final BindingFlow binding;
    private final TelegramProperties telegram;

    public GroupChats(ChatAccess access, BotReplies replies, ScheduleViews views, BindingFlow binding,
                      TelegramProperties telegram) {
        this.access = access;
        this.replies = replies;
        this.views = views;
        this.binding = binding;
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
            case "help" -> replies.send(chatId, BotTexts.groupHelp(), null);
            default -> {
                // інші команди в групі не наші — мовчимо, щоб не заважати розмові
            }
        }
    }
}
