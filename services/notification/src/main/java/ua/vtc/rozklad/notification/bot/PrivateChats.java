package ua.vtc.rozklad.notification.bot;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.bot.BotUpdate.ChatMessage;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.shared.ChatIds;

/**
 * Messages in a private chat: deep-link binding, commands, main keyboard buttons, the active
 * dialog (search or absence wizard), otherwise free text — an absence for teachers when it reads
 * like one, a search for everybody else.
 */
@Component
public class PrivateChats {

    private static final Logger log = LoggerFactory.getLogger(PrivateChats.class);

    private final ChatAccess access;
    private final ChatBindings bindings;
    private final DialogStore dialogs;
    private final BotReplies replies;
    private final ScheduleViews views;
    private final MyGroupFlow myGroup;
    private final SearchFlow search;
    private final AbsenceFlow absence;
    private final BindingFlow binding;
    private final TelegramProperties telegram;

    public PrivateChats(ChatAccess access, ChatBindings bindings, DialogStore dialogs, BotReplies replies,
                        ScheduleViews views, MyGroupFlow myGroup, SearchFlow search, AbsenceFlow absence,
                        BindingFlow binding, TelegramProperties telegram) {
        this.access = access;
        this.bindings = bindings;
        this.dialogs = dialogs;
        this.replies = replies;
        this.views = views;
        this.myGroup = myGroup;
        this.search = search;
        this.absence = absence;
        this.binding = binding;
        this.telegram = telegram;
    }

    public void onMessage(ChatMessage message) {
        long chatId = message.chat().id();
        Optional<Command> command = Command.parse(message.text());
        if (command.isPresent() && command.get().name().equals("start") && command.get().hasArgument()) {
            binding.redeem(message, command.get().argument());
            return;
        }
        if (!access.mayTalkTo(chatId)) {
            log.info("Ignored a message from unbound private chat {} (access BOUND_ONLY)", ChatIds.mask(chatId));
            return;
        }
        if (command.isPresent()) {
            dialogs.clear(chatId);
            onCommand(chatId, command.get());
            return;
        }
        Optional<MenuAction> action = MenuAction.match(message.text());
        if (action.isPresent()) {
            dialogs.clear(chatId);
            onMenu(chatId, action.get());
            return;
        }
        Optional<Dialog> dialog = dialogs.get(chatId);
        if (dialog.isPresent()) {
            switch (dialog.get().mode()) {
                case SEARCH -> search.query(chatId, message.text());
                case ABSENCE -> absence.onText(chatId, message.text());
            }
            return;
        }
        if (isTeacher(chatId) && AbsenceFlow.looksLikeAbsence(message.text())) {
            absence.onText(chatId, message.text());
        } else {
            search.query(chatId, message.text());
        }
    }

    private void onCommand(long chatId, Command command) {
        switch (command.name()) {
            case "start" -> greet(chatId);
            case "help" -> replies.send(chatId, BotTexts.help(isTeacher(chatId), telegram.botUsername()),
                    BotTexts.mainMenu(isTeacher(chatId)));
            case "today" -> views.today(chatId);
            case "tomorrow" -> views.tomorrow(chatId);
            case "group", "mygroup" -> myGroup.open(chatId);
            case "find", "search" -> search.start(chatId);
            case "bells" -> views.bells(chatId);
            case "parity" -> views.parity(chatId);
            case "mytomorrow" -> views.teacherTomorrow(chatId);
            case "absence" -> absence.start(chatId);
            case "cancel" -> replies.send(chatId, "Добре, скасовано.", BotTexts.mainMenu(isTeacher(chatId)));
            default -> replies.send(chatId, "Не знаю такої команди 🤔 Скористайтеся кнопками нижче або /help.",
                    BotTexts.mainMenu(isTeacher(chatId)));
        }
    }

    private void onMenu(long chatId, MenuAction action) {
        switch (action) {
            case TODAY -> views.today(chatId);
            case TOMORROW -> views.tomorrow(chatId);
            case MY_GROUP -> myGroup.open(chatId);
            case FIND -> search.start(chatId);
            case BELLS -> views.bells(chatId);
            case PARITY -> views.parity(chatId);
            case TEACHER_TOMORROW -> views.teacherTomorrow(chatId);
            case ABSENCE -> absence.start(chatId);
        }
    }

    private void greet(long chatId) {
        Optional<ChatBinding> teacher = bindings.teacherBinding(chatId);
        String groupCode = bindings.subscription(chatId).map(ChatBinding::getGroupCode).orElse(null);
        replies.send(chatId, BotTexts.greeting(groupCode, teacher.map(ChatBinding::getTitle).orElse(null)),
                BotTexts.mainMenu(teacher.isPresent()));
    }

    private boolean isTeacher(long chatId) {
        return bindings.teacherBinding(chatId).isPresent();
    }
}
