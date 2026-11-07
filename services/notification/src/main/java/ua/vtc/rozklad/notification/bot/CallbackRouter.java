package ua.vtc.rozklad.notification.bot;

import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.bot.BotUpdate.CallbackAction;
import ua.vtc.rozklad.notification.core.OwnerType;

/** Inline buttons → flows by the area prefix of the callback data. Every callback is answered. */
@Component
public class CallbackRouter {

    private final ChatAccess access;
    private final BotReplies replies;
    private final ScheduleViews views;
    private final MyGroupFlow myGroup;
    private final SearchFlow search;
    private final AbsenceFlow absence;
    private final SubstitutionReplyFlow substitutionReplies;

    public CallbackRouter(ChatAccess access, BotReplies replies, ScheduleViews views, MyGroupFlow myGroup,
                          SearchFlow search, AbsenceFlow absence, SubstitutionReplyFlow substitutionReplies) {
        this.access = access;
        this.replies = replies;
        this.views = views;
        this.myGroup = myGroup;
        this.search = search;
        this.absence = absence;
        this.substitutionReplies = substitutionReplies;
    }

    public void onCallback(CallbackAction action) {
        String data = action.data();
        if (CallbackData.NOOP.equals(data)) {
            replies.answer(action.callbackId());
            return;
        }
        if (action.chat() == null || action.messageId() == null) {
            replies.alert(action.callbackId(), BotTexts.STALE_BUTTON);
            return;
        }
        long chatId = action.chat().id();
        if (!access.mayTalkTo(chatId)) {
            replies.answer(action.callbackId(), "Бот недоступний у цьому чаті.");
            return;
        }
        String area = data.contains(":") ? data.substring(0, data.indexOf(':')) : data;
        try {
            switch (area) {
                case "sr" -> substitutionReplies.onCallback(action);
                case "ab" -> absence.onCallback(action);
                case "mg" -> myGroup(action);
                case "dg" -> myGroup.setDigest(action.callbackId(), chatId, action.messageId(),
                        CallbackData.DIGEST_ON.equals(data));
                case "sub" -> subscribe(action);
                case "d" -> day(action);
                case "s" -> searchPick(action);
                default -> replies.alert(action.callbackId(), BotTexts.STALE_BUTTON);
            }
        } catch (IllegalArgumentException | DateTimeParseException | IndexOutOfBoundsException e) {
            replies.alert(action.callbackId(), BotTexts.STALE_BUTTON);
        }
    }

    private void myGroup(CallbackAction action) {
        if (!action.chat().isPrivate()) {
            replies.alert(action.callbackId(), "Групу можна обрати лише в особистому чаті з ботом.");
            return;
        }
        String[] parts = action.data().split(":");
        long chatId = action.chat().id();
        switch (parts[1]) {
            case "courses" -> {
                myGroup.showCourses(chatId, action.messageId());
                replies.answer(action.callbackId());
            }
            case "c" -> {
                myGroup.showCourse(chatId, action.messageId(), Integer.parseInt(parts[2]));
                replies.answer(action.callbackId());
            }
            case "g" -> myGroup.choose(action.callbackId(), chatId, action.messageId(), UUID.fromString(parts[2]));
            default -> replies.alert(action.callbackId(), BotTexts.STALE_BUTTON);
        }
    }

    private void subscribe(CallbackAction action) {
        if (!action.chat().isPrivate()) {
            replies.alert(action.callbackId(), "Групу можна обрати лише в особистому чаті з ботом.");
            return;
        }
        UUID groupId = UUID.fromString(action.data().substring("sub:".length()));
        myGroup.subscribeFromCard(action.callbackId(), action.chat().id(), groupId);
    }

    /** {@code d:<g|t|r>:<id>:<yyyyMMdd>} — another day in the same card. */
    private void day(CallbackAction action) {
        String[] parts = action.data().split(":");
        views.showDay(action.chat().id(), action.messageId(), OwnerType.fromCode(parts[1]), UUID.fromString(parts[2]),
                CallbackData.parseDate(parts[3]), action.chat().isPrivate());
        replies.answer(action.callbackId());
    }

    /** {@code s:<g|t|r>:<id>} — a search result. */
    private void searchPick(CallbackAction action) {
        String[] parts = action.data().split(":");
        search.pick(action.chat().id(), action.messageId(), OwnerType.fromCode(parts[1]), UUID.fromString(parts[2]));
        replies.answer(action.callbackId());
    }
}
