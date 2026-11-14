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
import ua.vtc.rozklad.notification.core.CoreDtos.StudyDay;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.core.CoreQueries.DaySchedule;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.text.Plurals;
import ua.vtc.rozklad.notification.text.UaDates;

/**
 * «Сьогодні», «Завтра», day navigation, teacher's tomorrow, bells and parity. Days before core's public history
 * window (whole weeks from the Monday of {@code publicHistoryFrom}) are open only to staff chats
 * ({@link ChatBindings#isStaffChat}), which the bot asks core for with its service token; other chats get
 * {@link BotTexts#ARCHIVED_DAY} and no ◀️ past the window.
 */
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
        Card card;
        try {
            card = card(chatId, type, ownerId, date, privateChat);
        } catch (UpstreamUnavailableException e) {
            card = new Card(BotTexts.CORE_DOWN, InlineKeyboard.rows(List.of(InlineButton.callback(
                    "🔄 Спробувати ще раз", CallbackData.day(type, ownerId, date)))));
        } catch (UpstreamRejectedException e) {
            card = new Card("Розклад не знайдено 🤷 Можливо, групу чи викладача вже вилучили з каталогу.", null);
        }
        if (editMessageId != null) {
            replies.edit(chatId, editMessageId, card.text(), card.keyboard());
        } else {
            replies.send(chatId, card.text(), card.keyboard());
        }
    }

    private record Card(String text, InlineKeyboard keyboard) {
    }

    /** The day, or the archive card for a chat that may not open it. */
    private Card card(long chatId, OwnerType type, UUID ownerId, LocalDate date, boolean privateChat) {
        Window window = window(chatId, date);
        if (window.closes(date)) {
            return new Card(BotTexts.ARCHIVED_DAY, archived(type, ownerId, window.firstDay()));
        }
        try {
            return dayCard(chatId, type, ownerId, date, privateChat, window, window.archive(date));
        } catch (UpstreamRejectedException e) {
            if (!e.isHistoryNotPublic()) {
                throw e;
            }
            return movedWindow(chatId, type, ownerId, date, privateChat, window);
        }
    }

    private Card dayCard(long chatId, OwnerType type, UUID ownerId, LocalDate date, boolean privateChat,
                         Window window, boolean archive) {
        DaySchedule schedule = queries.day(type, ownerId, date, archive);
        LocalDate nextStudyDay = schedule.day().studyDay() ? null : nextStudyDay(type, ownerId, date, archive);
        return new Card(ScheduleCards.day(schedule, nextStudyDay, queries.teacherLabels()),
                navigation(chatId, type, ownerId, date, nextStudyDay, privateChat, window));
    }

    /**
     * core refused a day the bot's window let through without the token: the window moved meanwhile (meta is cached
     * for 30 s; midnight, or an admin lowered the days). The stale meta goes; a staff chat gets the day with the
     * service token, another chat the archive card with the button to the first day of the moved window.
     */
    private Card movedWindow(long chatId, OwnerType type, UUID ownerId, LocalDate date, boolean privateChat,
                             Window window) {
        queries.invalidateMeta();
        if (bindings.isStaffChat(chatId)) {
            return dayCard(chatId, type, ownerId, date, privateChat, window, true);
        }
        // межа з core, а не з годинника бота: core відмовив, тож вона вже після цього дня
        LocalDate from = queries.meta().publicHistoryFrom();
        LocalDate firstDay = from == null ? StudyDays.weekStart(today()) : StudyDays.weekStart(from);
        return new Card(BotTexts.ARCHIVED_DAY, archived(type, ownerId, firstDay));
    }

    /** Hint for a day without lessons; a failed lookup only loses the hint, not the card. */
    private LocalDate nextStudyDay(OwnerType type, UUID ownerId, LocalDate date, boolean archive) {
        try {
            return queries.nextStudyDay(type, ownerId, date, archive).orElse(null);
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            return null;
        }
    }

    /**
     * The public window for this chat when {@code earliest} may fall before it. The current week and later are open
     * to every chat, so they need neither core's meta nor the chat's bindings.
     */
    private Window window(long chatId, LocalDate earliest) {
        if (!isBeforeCurrentWeek(earliest)) {
            return Window.OPEN;
        }
        LocalDate from = queries.meta().publicHistoryFrom();
        if (from == null) {
            return Window.OPEN; // старший core: вікна немає, усе публічне
        }
        return new Window(StudyDays.weekStart(from), bindings.isStaffChat(chatId));
    }

    private boolean isBeforeCurrentWeek(LocalDate date) {
        return date.isBefore(StudyDays.weekStart(today()));
    }

    /**
     * The window that decides ◀️ to {@code previous}: the card's own when the card is before the current week (it
     * read the meta already), else the previous day's, which needs the meta only on the current week's Monday. core
     * unreachable there shows ◀️: the card must not fail over a button, and pressing it checks the window again.
     */
    private Window backWindow(long chatId, LocalDate date, LocalDate previous, Window card) {
        if (isBeforeCurrentWeek(date)) {
            return card;
        }
        try {
            return window(chatId, previous);
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            return Window.OPEN;
        }
    }

    /** «🗄 … в архіві» with a button to the first day the chat may open. */
    private static InlineKeyboard archived(OwnerType type, UUID ownerId, LocalDate firstDay) {
        return InlineKeyboard.rows(List.of(InlineButton.callback(dayLabel(firstDay) + " ▶️",
                CallbackData.day(type, ownerId, firstDay))));
    }

    /**
     * Core's public window as the bot applies it: whole weeks from {@code firstDay} ({@code null}: no limit).
     * A staff chat goes further back with the service token ({@link #archive}); another chat stops at it.
     */
    private record Window(LocalDate firstDay, boolean staff) {

        static final Window OPEN = new Window(null, false);

        boolean closes(LocalDate date) {
            return firstDay != null && !staff && date.isBefore(firstDay);
        }

        boolean archive(LocalDate date) {
            return firstDay != null && staff && date.isBefore(firstDay);
        }
    }

    /**
     * ◀️ ▶️ to the previous/next weekday; on a day without lessons ▶️ jumps to the next study day. A chat without
     * the archive gets no ◀️ on the first day of the public window.
     */
    private InlineKeyboard navigation(long chatId, OwnerType type, UUID ownerId, LocalDate date,
                                      LocalDate nextStudyDay, boolean privateChat, Window window) {
        LocalDate previous = StudyDays.previous(date);
        LocalDate next = nextStudyDay != null ? nextStudyDay : StudyDays.next(date);
        List<List<InlineButton>> rows = new ArrayList<>();
        InlineButton forward = InlineButton.callback(dayLabel(next) + " ▶️", CallbackData.day(type, ownerId, next));
        rows.add(backWindow(chatId, date, previous, window).closes(previous) ? List.of(forward) : List.of(
                InlineButton.callback("◀️ " + dayLabel(previous), CallbackData.day(type, ownerId, previous)),
                forward));
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
                        : " на " + escape(UaDates.weekdayDayMonthAccusative(date)));
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
        // звичайний текст, а не <pre>: номер пари й час однакової ширини (цифри в шрифтах Telegram
        // табличні), тож стовпчик рівний і без моноширинного блоку з кнопкою «копіювати»
        text.append('\n');
        for (BellSlot slot : bells.slots()) {
            String line = slot.pair() + " · " + UaDates.timeRange(slot.startsAt(), slot.endsAt());
            text.append('\n');
            if (current != null && current.number() == slot.pair()) {
                text.append(b(line)).append(" ◀ зараз");
            } else {
                text.append(escape(line));
            }
        }
        if (bells.profile() != null && bells.profile().name() != null) {
            text.append("\n\n").append(i(Plurals.agreeShifts(bells.profile().name())));
        }
        return text.toString();
    }

    public void parity(long chatId) {
        try {
            replies.send(chatId, parityText(queries.meta(), today()), null);
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            replies.send(chatId, BotTexts.CORE_DOWN, null);
        }
    }

    /**
     * This week's parity; on a weekend or a holiday also the week of the next study day (from core's
     * meta, which knows holidays and parity overrides), because that is the week people ask about.
     */
    static String parityText(PublicMeta meta, LocalDate today) {
        StudyDay next = meta.nextStudyDay();
        boolean nextKnown = !meta.studyDay() && next != null && next.date() != null && next.parity() != null
                && next.parity() != Parity.BOTH;
        boolean weekKnown = meta.parity() != null && meta.parity() != Parity.BOTH && meta.semester() != null;
        if (!weekKnown && !nextKnown) {
            return "🔢 Зараз немає навчального тижня — ні чисельника, ні знаменника 🙂";
        }
        StringBuilder text = new StringBuilder("🔢 ");
        if (weekKnown) {
            text.append("Цього тижня — ").append(b(UaDates.parity(meta.parity()).toUpperCase(UK)));
            if (meta.weekNumber() != null) {
                text.append(" (").append(meta.weekNumber()).append("-й тиждень)");
            }
        } else {
            text.append("Зараз немає навчального тижня.");
        }
        if (nextKnown) {
            LocalDate base = meta.today() != null ? meta.today() : today;
            String when = next.date().equals(base.plusDays(1)) ? "Завтра" : "Наступний навчальний день";
            text.append('\n').append(when).append(" (").append(escape(ScheduleCards.dayName(next.date())))
                    .append(") — ").append(b(UaDates.parity(next.parity())));
            if (next.weekNumber() != null) {
                text.append(", ").append(next.weekNumber()).append("-й тиждень");
            }
        }
        if (meta.semester() != null && meta.semester().name() != null) {
            text.append('\n').append(i(meta.semester().name()));
        }
        return text.toString();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
