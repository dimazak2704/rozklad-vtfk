package ua.vtc.rozklad.notification.bot;

import static ua.vtc.rozklad.notification.text.Html.b;
import static ua.vtc.rozklad.notification.text.Html.escape;
import static ua.vtc.rozklad.notification.text.Html.i;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.core.CoreDtos.BellSchedule;
import ua.vtc.rozklad.notification.core.CoreDtos.BellSlot;
import ua.vtc.rozklad.notification.core.CoreDtos.PairNow;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicMeta;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.core.CoreQueries.DaySchedule;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.text.UaDates;

/** «Сьогодні», «Завтра», day navigation, teacher's tomorrow, bells and parity. */
@Component
public class ScheduleViews {

    private static final Locale UK = Locale.forLanguageTag("uk");

    private final CoreQueries queries;
    private final ChatBindings bindings;
    private final BotReplies replies;
    private final MyGroupFlow myGroup;
    private final Clock clock;

    public ScheduleViews(CoreQueries queries, ChatBindings bindings, BotReplies replies, MyGroupFlow myGroup,
                         Clock clock) {
        this.queries = queries;
        this.bindings = bindings;
        this.replies = replies;
        this.myGroup = myGroup;
        this.clock = clock;
    }

    public void today(long chatId) {
        showForChat(chatId, today());
    }

    public void tomorrow(long chatId) {
        showForChat(chatId, today().plusDays(1));
    }

    /** Subscribed group first, otherwise the teacher's own schedule, otherwise ask for a group. */
    private void showForChat(long chatId, LocalDate date) {
        Optional<ChatBinding> subscription = bindings.subscription(chatId);
        if (subscription.isPresent()) {
            showDay(chatId, null, OwnerType.GROUP, subscription.get().getGroupId(), date, true);
            return;
        }
        Optional<ChatBinding> teacher = bindings.teacherBinding(chatId);
        if (teacher.isPresent()) {
            showDay(chatId, null, OwnerType.TEACHER, teacher.get().getTeacherId(), date, true);
            return;
        }
        replies.send(chatId, BotTexts.CHOOSE_GROUP_FIRST, null);
        myGroup.showCourses(chatId, null);
    }

    public void teacherTomorrow(long chatId) {
        Optional<ChatBinding> teacher = bindings.teacherBinding(chatId);
        if (teacher.isEmpty()) {
            replies.send(chatId, BotTexts.TEACHERS_ONLY, null);
            return;
        }
        showDay(chatId, null, OwnerType.TEACHER, teacher.get().getTeacherId(), today().plusDays(1), true);
    }

    /** {@code /today}, {@code /tomorrow} in a group chat bound to a group. */
    public void groupChatDay(long chatId, int daysFromToday) {
        Optional<ChatBinding> groupChat = bindings.groupChat(chatId);
        if (groupChat.isEmpty()) {
            replies.send(chatId, "Цей чат ще не прив’язаний до групи. Надішліть сюди /bind КОД — код дає "
                    + "диспетчер у веб-панелі.", null);
            return;
        }
        showDay(chatId, null, OwnerType.GROUP, groupChat.get().getGroupId(), today().plusDays(daysFromToday), false);
    }

    /**
     * One day card with ◀️ ▶️ navigation. {@code editMessageId} replaces an existing card;
     * {@code privateChat} adds «Зробити моєю групою» for groups the chat does not follow yet.
     */
    public void showDay(long chatId, Long editMessageId, OwnerType type, UUID ownerId, LocalDate date,
                        boolean privateChat) {
        String text;
        InlineKeyboard keyboard;
        try {
            DaySchedule schedule = queries.day(type, ownerId, date);
            text = ScheduleCards.day(schedule);
            keyboard = navigation(chatId, type, ownerId, date, privateChat);
        } catch (UpstreamUnavailableException e) {
            text = BotTexts.CORE_DOWN;
            keyboard = InlineKeyboard.rows(List.of(InlineButton.callback("🔄 Спробувати ще раз",
                    CallbackData.day(type, ownerId, date))));
        } catch (UpstreamRejectedException e) {
            text = "Розклад не знайдено 🤷 Можливо, групу чи викладача вже вилучили з каталогу.";
            keyboard = null;
        }
        if (editMessageId != null) {
            replies.edit(chatId, editMessageId, text, keyboard);
        } else {
            replies.send(chatId, text, keyboard);
        }
    }

    private InlineKeyboard navigation(long chatId, OwnerType type, UUID ownerId, LocalDate date,
                                      boolean privateChat) {
        LocalDate previous = StudyDays.previous(date);
        LocalDate next = StudyDays.next(date);
        List<List<InlineButton>> rows = new ArrayList<>();
        rows.add(List.of(
                InlineButton.callback("◀️ " + dayLabel(previous), CallbackData.day(type, ownerId, previous)),
                InlineButton.callback(dayLabel(next) + " ▶️", CallbackData.day(type, ownerId, next))));
        if (privateChat && type == OwnerType.GROUP) {
            boolean following = bindings.subscription(chatId)
                    .map(subscription -> ownerId.equals(subscription.getGroupId()))
                    .orElse(false);
            if (!following) {
                rows.add(List.of(InlineButton.callback("⭐ Зробити моєю групою", CallbackData.subscribe(ownerId))));
            }
        }
        return InlineKeyboard.of(rows);
    }

    private static String dayLabel(LocalDate date) {
        return UaDates.weekdayShort(date) + " " + UaDates.shortDate(date);
    }

    public void bells(long chatId) {
        try {
            PublicMeta meta = queries.meta();
            LocalDate today = meta.today() != null ? meta.today() : today();
            LocalDate date = StudyDays.onOrAfter(today);
            BellSchedule bells = queries.bells(date);
            replies.send(chatId, bellsText(meta, bells, date, today), null);
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            replies.send(chatId, BotTexts.CORE_DOWN, null);
        }
    }

    static String bellsText(PublicMeta meta, BellSchedule bells, LocalDate date, LocalDate today) {
        boolean isToday = date.equals(today);
        StringBuilder text = new StringBuilder("🔔 ").append(b("Дзвінки"))
                .append(isToday ? " · " + escape(UaDates.weekday(date))
                        : " на " + escape(UaDates.weekdayDayMonth(date)));
        if (bells.slots().isEmpty()) {
            return text.append("\n\nРозклад дзвінків на цей день не задано.").toString();
        }
        PairNow current = isToday && meta.studyDay() ? meta.currentPair() : null;
        PairNow next = isToday && meta.studyDay() ? meta.nextPair() : null;
        if (current != null) {
            text.append("\nЗараз ").append(b(current.number() + " пара"));
            if (current.minutesLeft() != null) {
                text.append(" — до кінця ").append(current.minutesLeft()).append(" хв");
            }
        } else if (next != null && next.startsAt() != null) {
            text.append("\nПерерва — ").append(b(next.number() + " пара")).append(" о ")
                    .append(UaDates.time(next.startsAt()));
        } else if (isToday && meta.studyDay()) {
            text.append("\nПари на сьогодні закінчилися 🎉");
        }
        text.append("\n\n<pre>");
        for (BellSlot slot : bells.slots()) {
            text.append(slot.pair()).append("  ").append(UaDates.timeRange(slot.startsAt(), slot.endsAt()));
            if (current != null && current.number() == slot.pair()) {
                text.append("  ◀ зараз");
            }
            text.append('\n');
        }
        text.setLength(text.length() - 1);
        text.append("</pre>");
        if (bells.profile() != null && bells.profile().name() != null) {
            text.append('\n').append(i(bells.profile().name()));
        }
        return text.toString();
    }

    public void parity(long chatId) {
        try {
            replies.send(chatId, parityText(queries.meta()), null);
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            replies.send(chatId, BotTexts.CORE_DOWN, null);
        }
    }

    static String parityText(PublicMeta meta) {
        if (meta.parity() == null || meta.parity() == Parity.BOTH || meta.semester() == null) {
            return "🔢 Зараз немає навчального тижня — ні чисельника, ні знаменника 🙂";
        }
        StringBuilder text = new StringBuilder("🔢 Цього тижня — ")
                .append(b(UaDates.parity(meta.parity()).toUpperCase(UK)));
        if (meta.weekNumber() != null) {
            text.append(" (").append(meta.weekNumber()).append("-й тиждень)");
        }
        if (meta.semester().name() != null) {
            text.append('\n').append(i(meta.semester().name()));
        }
        return text.toString();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
