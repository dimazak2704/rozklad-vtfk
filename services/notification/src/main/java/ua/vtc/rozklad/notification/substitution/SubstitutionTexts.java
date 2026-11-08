package ua.vtc.rozklad.notification.substitution;

import static ua.vtc.rozklad.notification.text.Html.b;
import static ua.vtc.rozklad.notification.text.Html.escape;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.notification.config.InfrastructureConfig;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineButton;
import ua.vtc.rozklad.notification.telegram.ReplyMarkup.InlineKeyboard;
import ua.vtc.rozklad.notification.text.UaDates;

/** Texts and buttons of substitution requests (UI.md §5) and the related admin notices. */
public final class SubstitutionTexts {

    public static final String CONFIRM_PREFIX = "sr:c:";
    public static final String DECLINE_PREFIX = "sr:d:";

    private SubstitutionTexts() {
    }

    public static String request(SubstitutionRequest request, boolean reminder) {
        String title = reminder ? "⏰ " + b("Нагадування: потрібна ваша відповідь") : "🙋 " + b("Прохання про заміну");
        StringBuilder text = new StringBuilder(title).append("\n\n").append(details(request));
        if (request.getDeadline() != null) {
            text.append("\n\nБудь ласка, дайте відповідь до ").append(escape(deadline(request.getDeadline()))).append('.');
        }
        return text.toString();
    }

    public static InlineKeyboard buttons(UUID changeId) {
        return InlineKeyboard.rows(List.of(
                InlineButton.callback("✅ Підтверджую", CONFIRM_PREFIX + changeId),
                InlineButton.callback("❌ Не можу", DECLINE_PREFIX + changeId)));
    }

    /** Final text of a request message; {@code null} for non-final statuses. */
    public static String finalState(SubstitutionRequest request, ChangeStatus status) {
        String outcome = outcome(status);
        if (outcome == null) {
            return null;
        }
        String body = request == null ? b("Прохання про заміну") : "🙋 " + b("Прохання про заміну") + "\n\n"
                + details(request);
        return body + "\n\n" + outcome;
    }

    /** Request message that can no longer be answered. */
    public static String outdated(SubstitutionRequest request) {
        String body = request == null ? b("Прохання про заміну") : "🙋 " + b("Прохання про заміну") + "\n\n"
                + details(request);
        return body + "\n\nℹ️ " + b("Цей запит уже неактуальний.");
    }

    static String outcome(ChangeStatus status) {
        return switch (status) {
            case CONFIRMED -> "✅ " + b("Ви підтвердили заміну.") + " Дякуємо!";
            case DECLINED -> "❌ " + b("Ви відмовилися від заміни.") + " Диспетчер підбере іншого викладача.";
            case CANCELLED -> "🚫 " + b("Заміну скасовано.") + " Нічого робити не потрібно.";
            case APPLIED -> "📄 " + b("Заміну внесено до розпорядження.") + " Дякуємо!";
            case DRAFT, PENDING_CONFIRMATION -> null;
        };
    }

    static String details(SubstitutionRequest request) {
        StringBuilder text = new StringBuilder()
                .append("📅 ").append(escape(UaDates.capitalize(UaDates.weekdayDayMonth(request.getLessonDate()))))
                .append("\n🕘 ").append(request.getPair()).append(" пара");
        String time = UaDates.timeRange(request.getStartsAt(), request.getEndsAt());
        if (!time.isEmpty()) {
            text.append(" · ").append(time);
        }
        if (request.getGroupCode() != null) {
            text.append("\n👥 ").append(escape(request.getGroupCode()));
            if (request.getSubgroup() > 0) {
                text.append(" (").append(request.getSubgroup()).append(" підгрупа)");
            }
        }
        if (notBlank(request.getDisciplineName())) {
            text.append("\n📘 ").append(escape(request.getDisciplineName()));
        }
        if (notBlank(request.getRoomName())) {
            text.append("\n🚪 Аудиторія ").append(escape(request.getRoomName()));
        }
        if (notBlank(request.getOriginalTeacherName())) {
            text.append("\n👤 Замість: ").append(escape(request.getOriginalTeacherName()));
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

    public static String adminDeclined(String teacherName, SubstitutionRequest request, String link) {
        return "❌ " + b("Відмова від заміни") + "\n" + escape(teacherName) + " не може провести пару: "
                + shortDetails(request) + "\nПідберіть іншого викладача." + link;
    }

    public static String adminDeclinedUnknown(String teacherName, java.time.LocalDate date, String groupCode,
                                              String link) {
        return "❌ " + b("Відмова від заміни") + "\n" + escape(teacherName) + " не може провести пару"
                + (date == null ? "" : " " + escape(UaDates.weekdayDayMonth(date)))
                + (groupCode == null ? "" : " у групі " + escape(groupCode)) + ".\nПідберіть іншого викладача." + link;
    }

    /** One warning for all unanswered requests of a teacher without Telegram on one date. */
    public static String adminNoTelegram(String teacherName, List<SubstitutionRequest> requests, String link) {
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
