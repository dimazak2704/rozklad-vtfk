package ua.vtc.rozklad.notification.bot;

import static ua.vtc.rozklad.notification.text.Html.b;
import static ua.vtc.rozklad.notification.text.Html.escape;
import static ua.vtc.rozklad.notification.text.Html.i;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.bot.BotUpdate.CallbackAction;
import ua.vtc.rozklad.notification.bot.Dialog.AbsenceDraft;
import ua.vtc.rozklad.notification.bot.Dialog.AbsenceDraft.Step;
import ua.vtc.rozklad.notification.core.CoreClient;
import ua.vtc.rozklad.notification.core.CoreClient.AbsenceReport;
import ua.vtc.rozklad.notification.core.ImporterClient;
import ua.vtc.rozklad.notification.core.ImporterClient.AbsenceParse;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.text.UaDates;

/**
 * Teacher reports an absence (UI.md §5): dates (today/tomorrow or an inline calendar of study
 * days), pairs (whole day or picked), reason (seen only by the dispatcher), confirmation → core
 * {@code POST /api/internal/absences} with source BOT. Free text («4.11 не буду, лікарняний») is
 * parsed by importer's AI and echoed back for confirmation. One message is edited step by step.
 */
@Component
public class AbsenceFlow {

    static final int MAX_TEXT = 500;
    static final int MAX_DAYS_AHEAD = 90;
    static final double MIN_CONFIDENCE = 0.6;
    static final int MAX_PAIR = 8;

    private static final Logger log = LoggerFactory.getLogger(AbsenceFlow.class);
    private static final Pattern ABSENCE_HINT = Pattern.compile(
            "не\\s*буду|не\\s*зможу|не\\s*прийду|відсутн|хвор|лікарнян|відпуст|відрядж",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String HEADER = "🤒 " + b("Повідомлення про відсутність");
    private static final Set<String> NEEDS_START_DATE = Set.of("one", "range", "all", "pick", "p",
            "r");

    private final ChatBindings bindings;
    private final DialogStore dialogs;
    private final BotReplies replies;
    private final CoreClient core;
    private final ImporterClient importer;
    private final Clock clock;

    public AbsenceFlow(ChatBindings bindings, DialogStore dialogs, BotReplies replies, CoreClient core,
                       ImporterClient importer, Clock clock) {
        this.bindings = bindings;
        this.dialogs = dialogs;
        this.replies = replies;
        this.core = core;
        this.importer = importer;
        this.clock = clock;
    }

    /** Free text of a teacher that should go to AI parsing instead of the search. */
    public static boolean looksLikeAbsence(String text) {
        return text != null && ABSENCE_HINT.matcher(text).find();
    }

    public void start(long chatId) {
        if (bindings.teacherBinding(chatId).isEmpty()) {
            replies.send(chatId, BotTexts.TEACHERS_ONLY, null);
            return;
        }
        AbsenceDraft draft = AbsenceDraft.start(null);
        dialogs.save(chatId, Dialog.absence(draft));
        Screen screen = render(draft, today());
        replies.send(chatId, screen.text(), screen.keyboard());
    }

    /** «4.11 не буду, лікарняний»: AI → echo for confirmation; anything unclear → buttons. */
    public void onText(long chatId, String text) {
        Optional<ChatBinding> teacher = bindings.teacherBinding(chatId);
        if (teacher.isEmpty()) {
            replies.send(chatId, BotTexts.TEACHERS_ONLY, null);
            return;
        }
        String message = text.strip();
        if (message.length() > MAX_TEXT) {
            replies.send(chatId, "Напишіть, будь ласка, коротше: дата, пари й причина.", null);
            return;
        }
        LocalDate today = today();
        AbsenceParse parse;
        try {
            parse = importer.parseAbsence(message, teacher.get().getTeacherId(), today);
        } catch (UpstreamUnavailableException | UpstreamRejectedException e) {
            log.warn("AI parsing of an absence failed: {}", e.getMessage());
            restartWithButtons(chatId, "😕 Не вдалося розпізнати текст — сервіс розпізнавання зараз недоступний.\n"
                    + "Оберіть дату кнопками:", today);
            return;
        }
        Optional<String> problem = problem(parse, today);
        if (problem.isPresent()) {
            restartWithButtons(chatId, "🤔 " + escape(problem.get())
                    + "\nУточніть, будь ласка, текст або оберіть дату кнопками:", today);
            return;
        }
        List<Integer> pairs = parse.pairs() == null || parse.pairs().isEmpty()
                ? null
                : parse.pairs().stream().distinct().sorted().toList();
        LocalDate to = parse.dateTo() == null ? parse.dateFrom() : parse.dateTo();
        Step step = parse.reasonCategory() == null ? Step.REASON : Step.CONFIRM;
        AbsenceDraft draft = new AbsenceDraft(step, parse.dateFrom(), to, pairs, parse.reasonCategory(), null,
                message);
        dialogs.save(chatId, Dialog.absence(draft));
        Screen screen = step == Step.CONFIRM
                ? confirmScreen(draft, "Я зрозумів так:", "Все правильно?", "✅ Так, надіслати")
                : render(draft, today);
        replies.send(chatId, screen.text(), screen.keyboard());
    }

    /** {@code ab:*} buttons; the chat and message are known (checked by the router). */
    public void onCallback(CallbackAction action) {
        long chatId = action.chat().id();
        long messageId = action.messageId();
        String data = action.data();
        if (CallbackData.ABSENCE_CANCEL.equals(data)) {
            dialogs.clear(chatId);
            replies.edit(chatId, messageId, "Добре, скасовано. Якщо що — я поруч 🙂", null);
            replies.answer(action.callbackId());
            return;
        }
        Optional<AbsenceDraft> current = dialogs.get(chatId)
                .filter(dialog -> dialog.mode() == Dialog.Mode.ABSENCE)
                .map(Dialog::absence);
        if (current.isEmpty()) {
            replies.alert(action.callbackId(), BotTexts.STALE_BUTTON);
            return;
        }
        AbsenceDraft draft = current.get();
        if (CallbackData.ABSENCE_SEND.equals(data)) {
            submit(action, draft);
            return;
        }
        LocalDate today = today();
        Optional<AbsenceDraft> next;
        try {
            next = next(draft, data.split(":"), today, action.callbackId());
        } catch (DateTimeParseException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            replies.alert(action.callbackId(), BotTexts.STALE_BUTTON);
            return;
        }
        if (next.isEmpty()) {
            return;
        }
        dialogs.save(chatId, Dialog.absence(next.get()));
        Screen screen = render(next.get(), today);
        replies.edit(chatId, messageId, screen.text(), screen.keyboard());
        replies.answer(action.callbackId());
    }

    /** New draft for a button, or empty when the press was refused (an alert was shown). */
    private Optional<AbsenceDraft> next(AbsenceDraft draft, String[] parts, LocalDate today, String callbackId) {
        String action = parts[1];
        if (NEEDS_START_DATE.contains(action) && draft.dateFrom() == null) {
            // кнопка зі старого повідомлення майстра: першого дня ще не обрано
            throw new IllegalArgumentException("Absence action " + action + " before a start date");
        }
        return switch (action) {
            case "d" -> {
                LocalDate date = CallbackData.parseDate(parts[2]);
                if (draft.step() == Step.DATE_TO) {
                    if (date.isBefore(draft.dateFrom())) {
                        replies.alert(callbackId, "Останній день не може бути раніше за перший.");
                        yield Optional.empty();
                    }
                    yield Optional.of(draft.to(date).at(Step.PAIRS));
                }
                if (date.isBefore(today)) {
                    replies.alert(callbackId, "Ця дата вже минула.");
                    yield Optional.empty();
                }
                yield Optional.of(draft.from(date).at(Step.RANGE));
            }
            case "cal" -> Optional.of(draft.showing(CallbackData.parseMonth(parts[2])));
            case "one" -> Optional.of(draft.to(draft.dateFrom()).at(Step.PAIRS));
            case "range" -> Optional.of(draft.showing(YearMonth.from(draft.dateFrom())).at(Step.DATE_TO));
            case "all" -> Optional.of(draft.withPairs(null).at(Step.REASON));
            case "pick" -> Optional.of(draft.withPairs(List.of()).at(Step.PAIR_PICK));
            case "p" -> {
                if ("ok".equals(parts[2])) {
                    if (draft.pairs() == null || draft.pairs().isEmpty()) {
                        replies.alert(callbackId, "Позначте хоча б одну пару.");
                        yield Optional.empty();
                    }
                    yield Optional.of(draft.at(Step.REASON));
                }
                yield Optional.of(draft.withPairs(toggle(draft.pairs(), Integer.parseInt(parts[2]))));
            }
            case "r" -> Optional.of(draft.withReason(AbsenceReasonCategory.valueOf(parts[2])).at(Step.CONFIRM));
            // «Змінити» починає спочатку; власний текст викладача більше не відповідає новим даним
            case "edit" -> Optional.of(AbsenceDraft.start(null));
            case "back" -> Optional.of(back(draft));
            default -> throw new IllegalArgumentException("Unknown absence action " + action);
        };
    }

    private static List<Integer> toggle(List<Integer> pairs, int pair) {
        if (pair < 1 || pair > MAX_PAIR) {
            throw new IllegalArgumentException("pair out of range: " + pair);
        }
        List<Integer> selected = new ArrayList<>(pairs == null ? List.of() : pairs);
        if (!selected.remove(Integer.valueOf(pair))) {
            selected.add(pair);
        }
        return selected.stream().sorted().toList();
    }

    private static AbsenceDraft back(AbsenceDraft draft) {
        return switch (draft.step()) {
            case DATE_FROM -> draft.showing(null);
            case RANGE -> draft.showing(null).at(Step.DATE_FROM);
            case DATE_TO, PAIRS -> draft.at(Step.RANGE);
            case PAIR_PICK, REASON -> draft.at(Step.PAIRS);
            case CONFIRM -> draft.at(Step.REASON);
        };
    }

    private void submit(CallbackAction action, AbsenceDraft draft) {
        long chatId = action.chat().id();
        long messageId = action.messageId();
        Optional<ChatBinding> teacher = bindings.teacherBinding(chatId);
        if (teacher.isEmpty()) {
            replies.alert(action.callbackId(), "Цей чат більше не прив’язаний до викладача.");
            return;
        }
        if (draft.step() != Step.CONFIRM || draft.dateFrom() == null || draft.reason() == null) {
            replies.alert(action.callbackId(), BotTexts.STALE_BUTTON);
            return;
        }
        if (draft.dateFrom().isBefore(today())) {
            replies.alert(action.callbackId(), "Дата вже минула — натисніть «Змінити» й оберіть іншу.");
            return;
        }
        try {
            core.reportAbsence(new AbsenceReport(teacher.get().getTeacherId(), draft.dateFrom(), draft.lastDay(),
                    draft.pairs(), draft.reason(), draft.comment(), AbsenceSource.BOT));
        } catch (UpstreamRejectedException e) {
            String detail = e.detail() == null || e.detail().isBlank() ? "сервер відхилив дані" : e.detail();
            replies.edit(chatId, messageId, "⚠️ " + b("Не вдалося надіслати") + ": " + escape(detail)
                    + "\nЗмініть дані й спробуйте ще раз.", InlineKeyboard.rows(List.of(
                    InlineButton.callback("✏️ Змінити", CallbackData.ABSENCE_EDIT),
                    InlineButton.callback("✖️ Скасувати", CallbackData.ABSENCE_CANCEL))));
            replies.answer(action.callbackId());
            return;
        } catch (UpstreamUnavailableException e) {
            replies.alert(action.callbackId(), "Сервер розкладу зараз недоступний. Спробуйте ще раз за хвилину.");
            return;
        }
        dialogs.clear(chatId);
        replies.edit(chatId, messageId, doneText(draft), null);
        replies.answer(action.callbackId(), "Надіслано ✅");
        log.info("Absence reported from the bot for teacher {}", teacher.get().getTeacherId());
    }

    Screen render(AbsenceDraft draft, LocalDate today) {
        LocalDate max = today.plusDays(MAX_DAYS_AHEAD);
        return switch (draft.step()) {
            case DATE_FROM -> draft.month() == null
                    ? quickDates(today)
                    : new Screen(HEADER + "\nОберіть перший день відсутності:",
                    calendar(draft.month(), today, max).plusRow(backCancel()));
            case RANGE -> new Screen(HEADER + "\n📅 Початок: " + b(UaDates.weekdayDayMonth(draft.dateFrom()))
                    + "\nЦе один день чи кілька?", InlineKeyboard.rows(
                    List.of(InlineButton.callback("Лише цей день", CallbackData.ABSENCE_ONE_DAY),
                            InlineButton.callback("Кілька днів", CallbackData.ABSENCE_RANGE)),
                    backCancel()));
            case DATE_TO -> new Screen(HEADER + "\n📅 Початок: " + b(UaDates.weekdayDayMonth(draft.dateFrom()))
                    + "\nОберіть останній день відсутності:",
                    calendar(draft.month(), draft.dateFrom(), max).plusRow(backCancel()));
            case PAIRS -> new Screen(HEADER + "\n📅 " + b(UaDates.range(draft.dateFrom(), draft.lastDay()))
                    + "\nЯких пар це стосується?", InlineKeyboard.rows(
                    List.of(InlineButton.callback("Увесь день", CallbackData.ABSENCE_ALL_DAY),
                            InlineButton.callback("Обрати пари", CallbackData.ABSENCE_PICK_PAIRS)),
                    backCancel()));
            case PAIR_PICK -> new Screen(HEADER + "\n📅 " + b(UaDates.range(draft.dateFrom(), draft.lastDay()))
                    + "\nПозначте пари й натисніть «Готово»:", pairPicker(draft.pairs()));
            case REASON -> new Screen(HEADER + "\n📅 " + b(UaDates.range(draft.dateFrom(), draft.lastDay()))
                    + " · 🕘 " + escape(pairsText(draft.pairs())) + "\nПричина? " + i("Її бачить лише диспетчер."),
                    reasons());
            case CONFIRM -> confirmScreen(draft, "Перевірте, будь ласка:", "Надіслати диспетчеру?", "✅ Надіслати");
        };
    }

    private Screen quickDates(LocalDate today) {
        LocalDate first = StudyDays.onOrAfter(today);
        LocalDate second = StudyDays.next(first);
        InlineKeyboard keyboard = InlineKeyboard.rows(
                List.of(InlineButton.callback(quickLabel(first, today), CallbackData.absenceDate(first)),
                        InlineButton.callback(quickLabel(second, today), CallbackData.absenceDate(second))),
                List.of(InlineButton.callback("📅 Інша дата", CallbackData.absenceMonth(YearMonth.from(today)))),
                List.of(InlineButton.callback("✖️ Скасувати", CallbackData.ABSENCE_CANCEL)));
        return new Screen(HEADER + "\nКоли вас не буде?\n\nМожна й своїми словами — напишіть, наприклад: "
                + i("«4.11 не буду, лікарняний»"), keyboard);
    }

    private static String quickLabel(LocalDate date, LocalDate today) {
        String day = UaDates.weekdayShort(date) + " " + UaDates.shortDate(date);
        if (date.equals(today)) {
            return "Сьогодні, " + day;
        }
        return date.equals(today.plusDays(1)) ? "Завтра, " + day : day;
    }

    /** Study days (Mon–Fri) of a month; days outside {@code [min, max]} are not clickable. */
    static InlineKeyboard calendar(YearMonth month, LocalDate min, LocalDate max) {
        YearMonth shown = month.isBefore(YearMonth.from(min)) ? YearMonth.from(min) : month;
        List<List<InlineButton>> rows = new ArrayList<>();
        boolean hasPrevious = shown.isAfter(YearMonth.from(min));
        boolean hasNext = shown.isBefore(YearMonth.from(max));
        rows.add(List.of(
                hasPrevious ? InlineButton.callback("◀️", CallbackData.absenceMonth(shown.minusMonths(1))) : noop("·"),
                noop(UaDates.capitalize(UaDates.monthYear(shown.getYear(), shown.getMonthValue()))),
                hasNext ? InlineButton.callback("▶️", CallbackData.absenceMonth(shown.plusMonths(1))) : noop("·")));
        rows.add(List.of(noop("Пн"), noop("Вт"), noop("Ср"), noop("Чт"), noop("Пт")));
        LocalDate monday = shown.atDay(1).with(DayOfWeek.MONDAY);
        while (!monday.isAfter(shown.atEndOfMonth())) {
            List<InlineButton> week = new ArrayList<>(5);
            boolean anyInMonth = false;
            for (int offset = 0; offset < 5; offset++) {
                LocalDate day = monday.plusDays(offset);
                boolean inMonth = YearMonth.from(day).equals(shown);
                anyInMonth |= inMonth;
                week.add(inMonth && !day.isBefore(min) && !day.isAfter(max)
                        ? InlineButton.callback(Integer.toString(day.getDayOfMonth()), CallbackData.absenceDate(day))
                        : noop("·"));
            }
            if (anyInMonth) {
                rows.add(week);
            }
            monday = monday.plusWeeks(1);
        }
        return InlineKeyboard.of(rows);
    }

    private static InlineKeyboard pairPicker(List<Integer> selected) {
        List<Integer> chosen = selected == null ? List.of() : selected;
        List<InlineButton> buttons = IntStream.rangeClosed(1, MAX_PAIR)
                .mapToObj(pair -> InlineButton.callback(chosen.contains(pair) ? "✅ " + pair : Integer.toString(pair),
                        CallbackData.absencePair(pair)))
                .toList();
        return InlineKeyboard.grid(buttons, 4)
                .plusRow(List.of(InlineButton.callback("Готово", CallbackData.ABSENCE_PAIRS_DONE)))
                .plusRow(backCancel());
    }

    private static InlineKeyboard reasons() {
        return InlineKeyboard.rows(
                List.of(reason("🤒 Хвороба", AbsenceReasonCategory.SICK_LEAVE),
                        reason("🏖 Відпустка", AbsenceReasonCategory.VACATION)),
                List.of(reason("🧳 Відрядження", AbsenceReasonCategory.BUSINESS_TRIP),
                        reason("👤 Особисті", AbsenceReasonCategory.PERSONAL)),
                List.of(reason("📝 Інше", AbsenceReasonCategory.OTHER)),
                backCancel());
    }

    private static InlineButton reason(String label, AbsenceReasonCategory category) {
        return InlineButton.callback(label, CallbackData.absenceReason(category));
    }

    private static Screen confirmScreen(AbsenceDraft draft, String intro, String question, String sendLabel) {
        String text = b(intro) + "\n📅 " + escape(UaDates.range(draft.dateFrom(), draft.lastDay()))
                + "\n🕘 " + escape(pairsText(draft.pairs()))
                + "\n📝 " + escape(reasonText(draft.reason()))
                + "\n\n" + question;
        InlineKeyboard keyboard = InlineKeyboard.rows(
                List.of(InlineButton.callback(sendLabel, CallbackData.ABSENCE_SEND)),
                List.of(InlineButton.callback("✏️ Змінити", CallbackData.ABSENCE_EDIT),
                        InlineButton.callback("✖️ Скасувати", CallbackData.ABSENCE_CANCEL)));
        return new Screen(text, keyboard);
    }

    private static List<InlineButton> backCancel() {
        return List.of(InlineButton.callback("⬅️ Назад", CallbackData.ABSENCE_BACK),
                InlineButton.callback("✖️ Скасувати", CallbackData.ABSENCE_CANCEL));
    }

    private static InlineButton noop(String text) {
        return InlineButton.callback(text, CallbackData.NOOP);
    }

    private void restartWithButtons(long chatId, String intro, LocalDate today) {
        dialogs.save(chatId, Dialog.absence(AbsenceDraft.start(null)));
        replies.send(chatId, intro, quickDates(today).keyboard());
    }

    /** Why the AI result cannot be used, if it cannot. */
    static Optional<String> problem(AbsenceParse parse, LocalDate today) {
        if (parse == null) {
            return Optional.of("Не вдалося розібрати повідомлення.");
        }
        if (parse.clarification() != null && !parse.clarification().isBlank()) {
            return Optional.of(parse.clarification().strip());
        }
        if (parse.confidence() != null && parse.confidence() < MIN_CONFIDENCE) {
            return Optional.of("Не впевнений, що зрозумів правильно.");
        }
        if (parse.dateFrom() == null) {
            return Optional.of("Не знайшов у тексті дату.");
        }
        if (parse.dateTo() != null && parse.dateTo().isBefore(parse.dateFrom())) {
            return Optional.of("Кінцева дата раніша за початкову.");
        }
        if (parse.dateFrom().isBefore(today)) {
            return Optional.of("Дата " + UaDates.shortDate(parse.dateFrom()) + " уже минула.");
        }
        if (parse.dateFrom().isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            return Optional.of("Дата надто далеко наперед.");
        }
        if (parse.pairs() != null && parse.pairs().stream().anyMatch(pair -> pair == null || pair < 1 || pair > MAX_PAIR)) {
            return Optional.of("Номери пар мають бути від 1 до " + MAX_PAIR + ".");
        }
        return Optional.empty();
    }

    static String pairsText(List<Integer> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return "увесь день";
        }
        return pairs.size() == 1
                ? pairs.getFirst() + " пара"
                : "пари " + pairs.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    static String reasonText(AbsenceReasonCategory reason) {
        if (reason == null) {
            return "не вказано";
        }
        return switch (reason) {
            case SICK_LEAVE -> "хвороба / лікарняний";
            case VACATION -> "відпустка";
            case BUSINESS_TRIP -> "відрядження";
            case PERSONAL -> "особисті обставини";
            case OTHER -> "інше";
        };
    }

    private static String doneText(AbsenceDraft draft) {
        String wish = switch (draft.reason()) {
            case SICK_LEAVE -> "\nОдужуйте! 💙";
            case VACATION -> "\nГарного відпочинку! 🌿";
            default -> "";
        };
        return "✅ " + b("Готово!") + " Диспетчер отримав повідомлення:\n📅 "
                + escape(UaDates.range(draft.dateFrom(), draft.lastDay())) + " · 🕘 " + escape(pairsText(draft.pairs()))
                + "\nМи підберемо заміну." + wish;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    record Screen(String text, InlineKeyboard keyboard) {
    }
}
