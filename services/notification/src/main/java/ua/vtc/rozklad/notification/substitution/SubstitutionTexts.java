package ua.vtc.rozklad.notification.substitution;

import static ua.vtc.rozklad.notification.text.Html.b;
import static ua.vtc.rozklad.notification.text.Html.escape;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.notification.config.InfrastructureConfig;
import ua.vtc.rozklad.notification.substitution.SubstitutionRequest.Details;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.text.UaDates;

/**
 * Texts and buttons of substitution messages (UI.md §5) and the related admin notices: a request to confirm
 * (CONFIRM) and a notice about an assigned change (NOTIFY, DOMAIN §7). Both use the same callbacks: core
 * knows the change's mode and reads ✅/👍 as CONFIRM and ❌ as DECLINE.
 */
public final class SubstitutionTexts {

    public static final String CONFIRM_PREFIX = "sr:c:";
    public static final String DECLINE_PREFIX = "sr:d:";

    private static final String REQUEST_TITLE = "🙋 " + b("Прохання про заміну");
    private static final String NOTICE_TITLE = "📌 " + b("Вам поставили заміну");
    private static final String UPDATE_TITLE = "✏️ " + b("Заміну змінено");
    private static final String NOTICE_HINT = "Якщо не можете, натисніть «Не можу» — диспетчер обере інший варіант.";
    private static final String CONFIRMED_HINT = "Заміна вже підтверджена, відповідати не потрібно.";
    private static final String MUTUAL = "MUTUAL";

    private SubstitutionTexts() {
    }

    public static String request(SubstitutionRequest request, boolean reminder) {
        String title = reminder ? "⏰ " + b("Нагадування: потрібна ваша відповідь") : REQUEST_TITLE;
        StringBuilder text = new StringBuilder(title).append("\n\n").append(details(request));
        appendDeadline(text, request);
        return text.toString();
    }

    /**
     * The request (CONFIRM) or notice (NOTIFY) sent again because the dispatcher changed what the teacher sees
     * (the room): says what changed, then the usual details and the request's deadline, which does not restart.
     * A request already confirmed only informs: no deadline and nothing to answer.
     */
    public static String update(SubstitutionRequest request, Details before) {
        StringBuilder text = new StringBuilder(UPDATE_TITLE).append('\n').append(whatChanged(before, request.details()))
                .append("\n\n").append(details(request));
        if (request.isNotice()) {
            text.append("\n\n").append(NOTICE_HINT);
        } else if (request.isConfirmedRequest()) {
            text.append("\n\n").append(CONFIRMED_HINT);
        } else {
            appendDeadline(text, request);
        }
        return text.toString();
    }

    /** Buttons under {@link #update}: the mode's answer buttons; none for a request already confirmed. */
    public static InlineKeyboard updateKeyboard(SubstitutionRequest request) {
        if (request.isConfirmedRequest()) {
            return null;
        }
        return request.isNotice() ? noticeButtons(request.getChangeId()) : buttons(request.getChangeId());
    }

    /** The message body would read differently with {@code after} than with {@code before}. */
    public static boolean differs(Details before, Details after) {
        return !details(before).equals(details(after));
    }

    /** «Нова аудиторія: 214 (була 108)», «Змінено: дату, пару» or both in one line (Telegram HTML). */
    static String whatChanged(Details before, Details after) {
        return escape(changeSummary(before, after));
    }

    /** The same line as {@link #whatChanged} in plain text (e-mail). */
    public static String changeSummary(Details before, Details after) {
        List<String> other = new ArrayList<>();
        if (!Objects.equals(before.lessonDate(), after.lessonDate())) {
            other.add("дату");
        }
        if (before.pair() != after.pair()) {
            other.add("пару");
        } else if (!UaDates.timeRange(before.startsAt(), before.endsAt())
                .equals(UaDates.timeRange(after.startsAt(), after.endsAt()))) {
            other.add("час");
        }
        if (!Objects.equals(before.groupCode(), after.groupCode()) || before.subgroup() != after.subgroup()) {
            other.add("групу");
        }
        if (!Objects.equals(before.disciplineName(), after.disciplineName())
                || !Objects.equals(before.originalDisciplineName(), after.originalDisciplineName())) {
            other.add("дисципліну");
        }
        String room = roomChange(before.roomName(), after.roomName());
        if (room == null && other.isEmpty()) {
            return "Змінено деталі заміни";
        }
        if (room == null) {
            return "Змінено: " + String.join(", ", other);
        }
        return other.isEmpty() ? room : room + "; змінено також: " + String.join(", ", other);
    }

    /** {@code null} when the room reads the same. */
    private static String roomChange(String before, String after) {
        String was = notBlank(before) ? before : null;
        String now = notBlank(after) ? after : null;
        if (Objects.equals(was, now)) {
            return null;
        }
        if (now == null) {
            return "Аудиторію більше не вказано (була " + was + ")";
        }
        return "Нова аудиторія: " + now + (was == null ? "" : " (була " + was + ")");
    }

    private static void appendDeadline(StringBuilder text, SubstitutionRequest request) {
        if (request.getDeadline() != null) {
            text.append("\n\nБудь ласка, дайте відповідь до ").append(escape(deadline(request.getDeadline()))).append('.');
        }
    }

    public static InlineKeyboard buttons(UUID changeId) {
        return InlineKeyboard.rows(List.of(
                InlineButton.callback("✅ Підтверджую", CONFIRM_PREFIX + changeId),
                InlineButton.callback("❌ Не можу", DECLINE_PREFIX + changeId)));
    }

    /** NOTIFY: the change is already in force, the teacher is told and may object. */
    public static String notice(SubstitutionRequest request) {
        return NOTICE_TITLE + "\n\n" + details(request) + "\n\n" + NOTICE_HINT;
    }

    public static InlineKeyboard noticeButtons(UUID changeId) {
        return InlineKeyboard.rows(List.of(
                InlineButton.callback("👍 Добре", CONFIRM_PREFIX + changeId),
                InlineButton.callback("❌ Не можу", DECLINE_PREFIX + changeId)));
    }

    /** Final text of a request message; {@code null} for non-final statuses. */
    public static String finalState(SubstitutionRequest request, ChangeStatus status) {
        String outcome = outcome(status);
        if (outcome == null) {
            return null;
        }
        String body = request == null ? b("Прохання про заміну") : REQUEST_TITLE + "\n\n" + details(request);
        return body + "\n\n" + outcome;
    }

    /**
     * Text of a notice message for its state ({@code status}, {@code answer}); {@code null} while the change is
     * confirmed and unanswered (the message stays as sent). An unanswered notice of a published change keeps
     * its buttons ({@link #noticeKeyboard}).
     */
    public static String noticeState(SubstitutionRequest request, ChangeStatus status, ConfirmationDecision answer) {
        String outcome = noticeOutcome(status, answer);
        if (outcome == null) {
            return null;
        }
        String body = request == null ? b("Вам поставили заміну") : NOTICE_TITLE + "\n\n" + details(request);
        return body + "\n\n" + outcome;
    }

    public static String noticeState(SubstitutionRequest request) {
        return noticeState(request, request.getStatus(), request.getTeacherAnswer());
    }

    /** Buttons a notice keeps in this state: only while the change is in force and the teacher has not answered. */
    public static InlineKeyboard noticeKeyboard(UUID changeId, ChangeStatus status, ConfirmationDecision answer) {
        boolean inForce = status == ChangeStatus.CONFIRMED || status == ChangeStatus.APPLIED;
        return inForce && answer == null ? noticeButtons(changeId) : null;
    }

    /** What the message of a known request says now (a final state, or «неактуальний» if there is none). */
    public static String current(SubstitutionRequest request) {
        String text = request.isNotice() ? noticeState(request) : finalState(request, request.getStatus());
        return text != null ? text : outdated(request);
    }

    /** Short reply under the pressed button of a notice. */
    public static String noticeToast(ChangeStatus status, ConfirmationDecision answer) {
        if (answer == ConfirmationDecision.CONFIRM) {
            return "Дякуємо! 👍";
        }
        return status == ChangeStatus.APPLIED ? "Диспетчера повідомлено" : "Відмову передано диспетчеру";
    }

    /** Request message that can no longer be answered. */
    public static String outdated(SubstitutionRequest request) {
        String title = request != null && request.isNotice() ? NOTICE_TITLE : REQUEST_TITLE;
        String body = request == null ? b("Прохання про заміну") : title + "\n\n" + details(request);
        return body + "\n\nℹ️ " + b("Цей запит уже неактуальний.");
    }

    static String outcome(ChangeStatus status) {
        return switch (status) {
            case CONFIRMED -> "✅ " + b("Ви підтвердили заміну.") + " Дякуємо!";
            case DECLINED -> declined();
            case CANCELLED -> cancelled();
            case APPLIED -> "📄 " + b("Заміну внесено до розпорядження.") + " Дякуємо!";
            case DRAFT, PENDING_CONFIRMATION -> null;
        };
    }

    static String noticeOutcome(ChangeStatus status, ConfirmationDecision answer) {
        return switch (status) {
            case CONFIRMED -> answer == ConfirmationDecision.CONFIRM
                    ? "👍 " + b("Добре, заміну прийнято.") + " Дякуємо!" : null;
            case APPLIED -> {
                if (answer == ConfirmationDecision.DECLINE) {
                    yield "❗ " + b("Ви повідомили, що не можете.") + " Заміна вже в розпорядженні, тож диспетчера "
                            + "повідомлено: він внесе доповнення.";
                }
                yield answer == ConfirmationDecision.CONFIRM
                        ? "📄 " + b("Заміну внесено до розпорядження.") + " Дякуємо!"
                        : "📄 Заміну внесено до розпорядження.";
            }
            case DECLINED -> declined();
            case CANCELLED -> cancelled();
            case DRAFT, PENDING_CONFIRMATION -> null;
        };
    }

    private static String declined() {
        return "❌ " + b("Ви відмовилися від заміни.") + " Диспетчер обере інший варіант.";
    }

    private static String cancelled() {
        return "🚫 " + b("Заміну скасовано.") + " Нічого робити не потрібно.";
    }

    static String details(SubstitutionRequest request) {
        return details(request.details());
    }

    static String details(Details request) {
        StringBuilder text = new StringBuilder()
                .append("📅 ").append(escape(UaDates.capitalize(UaDates.weekdayDayMonth(request.lessonDate()))))
                .append("\n🕘 ").append(request.pair()).append(" пара");
        String time = UaDates.timeRange(request.startsAt(), request.endsAt());
        if (!time.isEmpty()) {
            text.append(" · ").append(time);
        }
        if (request.groupCode() != null) {
            text.append("\n👥 ").append(escape(request.groupCode()));
            if (request.subgroup() > 0) {
                text.append(" (").append(request.subgroup()).append(" підгрупа)");
            }
        }
        if (notBlank(request.disciplineName())) {
            text.append("\n📘 ").append(escape(request.disciplineName()));
        }
        boolean otherDiscipline = request.changeKind() == ChangeKind.REPLACE_DISCIPLINE
                && notBlank(request.originalDisciplineName());
        if (otherDiscipline) {
            // заміна іншою дисципліною групи: викладач веде свою дисципліну замість чужої
            text.append("\n🔁 Замість «").append(escape(request.originalDisciplineName())).append('»');
            if (notBlank(request.originalTeacherName())) {
                text.append(" (").append(escape(request.originalTeacherName())).append(')');
            }
            if (MUTUAL.equals(request.reasonCode())) {
                text.append(" (взаємна заміна)");
            }
        }
        if (notBlank(request.roomName())) {
            text.append("\n🚪 Аудиторія ").append(escape(request.roomName()));
        }
        if (!otherDiscipline && notBlank(request.originalTeacherName())) {
            text.append("\n👤 Замість: ").append(escape(request.originalTeacherName()));
        }
        return text.toString();
    }

    static String shortDetails(SubstitutionRequest request) {
        StringBuilder text = new StringBuilder(UaDates.weekdayDayMonth(request.getLessonDate()))
                .append(" · ").append(request.getPair()).append(" пара");
        if (request.getGroupCode() != null) {
            text.append(" · ").append(request.getGroupCode());
        }
        if (notBlank(request.getDisciplineName())) {
            text.append(" · ").append(request.getDisciplineName());
        }
        return escape(text.toString());
    }

    /** «Непийвода М.В. не може взяти 2 пару 4КІ1 у понеділок, 19 жовтня.» */
    private static String cannotTake(String teacherName, Integer pair, String groupCode, LocalDate date) {
        StringBuilder text = new StringBuilder(teacherName).append(" не може взяти ");
        text.append(pair == null ? "пару" : pair + " пару");
        if (groupCode != null) {
            text.append(' ').append(groupCode);
        }
        if (date != null) {
            text.append(" у ").append(UaDates.weekdayDayMonthAccusative(date));
        }
        return escape(text.toString());
    }

    public static String adminDeclined(String teacherName, SubstitutionRequest request, String link) {
        return "❌ " + b("Відмова від заміни") + "\n"
                + cannotTake(teacherName, request.getPair(), request.getGroupCode(), request.getLessonDate())
                + ". Оберіть інший варіант на сторінці змін." + link;
    }

    public static String adminDeclinedUnknown(String teacherName, LocalDate date, String groupCode, String link) {
        return "❌ " + b("Відмова від заміни") + "\n" + cannotTake(teacherName, null, groupCode, date)
                + ". Оберіть інший варіант на сторінці змін." + link;
    }

    /** NOTIFY: «Не можу» after the order was published — the order stays, an amendment is needed. */
    public static String adminObjection(String teacherName, Integer pair, String groupCode, LocalDate date,
                                        String link) {
        return "❗ " + b("Потрібне доповнення до розпорядження") + "\n" + cannotTake(teacherName, pair, groupCode, date)
                + ", а заміна вже в опублікованому розпорядженні. Оберіть інший варіант на сторінці змін — "
                + "він піде в доповнення." + link;
    }

    /**
     * One warning for all unanswered requests of a teacher without Telegram on one date. For NOTIFY the
     * changes are in force already, so the dispatcher has to tell the teacher himself.
     */
    public static String adminNoTelegram(String teacherName, List<SubstitutionRequest> requests, String link) {
        if (requests.getFirst().isNotice()) {
            return adminNotifyNoTelegram(teacherName, requests, link);
        }
        String title = "⚠️ " + b(teacherName + " не підключив Telegram");
        if (requests.size() == 1) {
            return title + "\nЗапит на заміну не надіслано: " + shortDetails(requests.getFirst())
                    + ".\nПідтвердьте заміну в панелі або зв’яжіться з викладачем." + link;
        }
        StringBuilder text = new StringBuilder(title)
                .append("\nЗапити на заміну не надіслано, ")
                .append(escape(UaDates.weekdayDayMonth(requests.getFirst().getLessonDate()))).append(':');
        for (SubstitutionRequest request : requests) {
            text.append("\n• ").append(pairDetails(request));
        }
        return text.append("\nПідтвердьте заміни в панелі або зв’яжіться з викладачем.").append(link).toString();
    }

    private static String adminNotifyNoTelegram(String teacherName, List<SubstitutionRequest> requests, String link) {
        String title = "⚠️ " + b(teacherName + " не підключив Telegram — повідомте про заміну самі");
        if (requests.size() == 1) {
            return title + "\nПовідомлення не надіслано: " + shortDetails(requests.getFirst()) + "." + link;
        }
        StringBuilder text = new StringBuilder(title)
                .append("\nПовідомлення не надіслано, ")
                .append(escape(UaDates.weekdayDayMonth(requests.getFirst().getLessonDate()))).append(':');
        for (SubstitutionRequest request : requests) {
            text.append("\n• ").append(pairDetails(request));
        }
        return text.append(link).toString();
    }

    /** {@code 2 пара · 4КІ1 · Дисципліна} (the date is said once above). */
    private static String pairDetails(SubstitutionRequest request) {
        StringBuilder text = new StringBuilder().append(request.getPair()).append(" пара");
        if (request.getGroupCode() != null) {
            text.append(" · ").append(request.getGroupCode());
        }
        if (notBlank(request.getDisciplineName())) {
            text.append(" · ").append(request.getDisciplineName());
        }
        return escape(text.toString());
    }

    public static String adminOverdue(String teacherName, SubstitutionRequest request, String link) {
        return "⏰ " + b("Немає відповіді щодо заміни") + "\n" + escape(teacherName) + " не відповів до "
                + escape(deadline(request.getDeadline())) + ": " + shortDetails(request) + "." + link;
    }

    /** {@code 18.10 о 19:00} in Kyiv time. */
    static String deadline(Instant deadline) {
        LocalDateTime local = LocalDateTime.ofInstant(deadline, InfrastructureConfig.KYIV);
        return UaDates.shortDate(local.toLocalDate()) + " о " + UaDates.time(local.toLocalTime());
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }
}
