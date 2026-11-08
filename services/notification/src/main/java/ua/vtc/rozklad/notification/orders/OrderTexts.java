package ua.vtc.rozklad.notification.orders;

import static ua.vtc.rozklad.notification.text.Html.b;
import static ua.vtc.rozklad.notification.text.Html.escape;
import static ua.vtc.rozklad.notification.text.Html.s;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.contracts.events.OrderPayload.Item;
import ua.vtc.rozklad.notification.text.Plurals;
import ua.vtc.rozklad.notification.text.Reasons;
import ua.vtc.rozklad.notification.text.UaDates;

/** Compact Telegram HTML for order rows: what changed for a group or a teacher, and the admin summary. */
public final class OrderTexts {

    private static final Locale UK = Locale.forLanguageTag("uk");

    private OrderTexts() {
    }

    /** Message to a group chat: only the group's rows. */
    public static String forGroup(OrderPayload order, String groupCode, List<Item> rows) {
        StringBuilder text = new StringBuilder()
                .append("📢 ").append(b(groupCode)).append(" · зміни на ")
                .append(escape(UaDates.weekdayDayMonth(order.date())))
                .append('\n').append(escape(orderLabel(order))).append(parityPart(order))
                .append("\n\n");
        text.append(rows.stream().map(row -> describe(row, false)).collect(Collectors.joining("\n\n")));
        text.append("\n\n").append("<i>Повний текст розпорядження — на зображенні.</i>");
        return text.toString();
    }

    /** Message to a teacher: the rows where the teacher is involved, with group codes. */
    public static String forTeacher(OrderPayload order, List<Item> rows) {
        StringBuilder text = new StringBuilder()
                .append("📢 ").append(b("Ваші зміни")).append(" на ")
                .append(escape(UaDates.weekdayDayMonth(order.date())))
                .append('\n').append(escape(orderLabel(order))).append(parityPart(order))
                .append("\n\n");
        text.append(rows.stream().map(row -> describe(row, true)).collect(Collectors.joining("\n\n")));
        return text.toString();
    }

    /** Summary for admin chats. */
    public static String adminSummary(OrderPayload order, int groupChats, int teachers, List<String> withoutTelegram,
                                      boolean picture, String link) {
        StringBuilder text = new StringBuilder()
                .append("📄 ").append(b("Опубліковано " + orderLabel(order).toLowerCase(UK)))
                .append('\n').append(escape(UaDates.capitalize(UaDates.weekdayDayMonth(order.date()))))
                .append(' ').append(order.date().getYear()).append(parityPart(order))
                .append("\nЗмін: ").append(order.items().size())
                .append(" · заміна аудиторій: ").append(order.roomChanges().size())
                .append(" · приміток: ").append(order.notes().size())
                .append("\nНадіслано: ").append(groupChats).append(' ').append(plural(groupChats, "чату групи",
                        "чатам груп", "чатам груп"))
                .append(", ").append(teachers).append(' ').append(plural(teachers, "викладачу", "викладачам",
                        "викладачам"));
        if (!withoutTelegram.isEmpty()) {
            text.append("\n⚠️ Без Telegram: ").append(escape(String.join(", ", withoutTelegram)));
        }
        if (!picture) {
            text.append("\n⚠️ Зображення не надсилалося: конвертер PDF недоступний.");
        }
        return text.append(link).toString();
    }

    public static String pictureCaption(OrderPayload order) {
        return escape(orderLabel(order) + " на " + UaDates.fullDate(order.date()));
    }

    /** «Розпорядження №35», «Доповнення №36». */
    static String orderLabel(OrderPayload order) {
        String kind = order.kind() == OrderKind.AMENDMENT ? "Доповнення" : "Розпорядження";
        return order.number() == null ? kind : kind + " №" + order.number();
    }

    private static String parityPart(OrderPayload order) {
        return order.parityLabel() == null || order.parityLabel().isBlank()
                ? ""
                : " · " + escape(order.parityLabel().toLowerCase(UK));
    }

    /** One row as one or two lines; {@code withGroup} prefixes the group code (teacher messages). */
    static String describe(Item row, boolean withGroup) {
        String pair = pairTitle(row.pairLabel());
        String head = withGroup && notBlank(row.groupCode()) ? b(row.groupCode() + ", " + pair) : b(pair);
        String from = lesson(row.fromDiscipline(), row.fromTeacher());
        String to = lesson(row.toDiscipline(), row.toTeacher());
        String room = notBlank(row.roomText()) ? ", ауд. " + escape(row.roomText()) : "";
        // коди причин («Відп.», «Зв.зам.») лишаються в документі розпорядження, у чатах — словами
        String reason = notBlank(row.reasonText()) ? " · " + escape(Reasons.inWords(row.reasonText())) : "";
        return switch (row.rowType()) {
            case REPLACEMENT -> "🔁 " + head + ": " + boldLesson(to) + room
                    + (from.isEmpty() ? "" : "\n      замість " + s(from)) + reason;
            case CANCELLATION -> "❌ " + head + ": пари не буде"
                    + (from.isEmpty() ? "" : "\n      " + s(from)) + reason;
            case ADDITIONAL -> "➕ " + head + ": додатково " + boldLesson(to) + room;
            case BLOCK -> "📌 " + head + ": " + boldLesson(from.isEmpty() ? to : from) + room;
            case FINISHED -> to.isEmpty()
                    ? "🏁 " + head + ": пари не буде (вичитано)" + (from.isEmpty() ? "" : "\n      " + s(from))
                    : "🔁 " + head + ": " + boldLesson(to) + room
                            + (from.isEmpty() ? "" : "\n      замість " + s(from)) + " · вичитано";
            case AS_SCHEDULED -> "↩️ " + head + ": за розкладом" + (to.isEmpty() ? "" : " — " + boldLesson(to)) + room;
            case ROOM_CHANGE -> "🚪 " + head + ": аудиторія " + b(nullToEmpty(row.roomText()))
                    + (from.isEmpty() ? "" : " (" + escape(from) + ")");
        };
    }

    /** «2» → «2 пара», «1-4» → «1–4 пари». */
    static String pairTitle(String label) {
        if (!notBlank(label)) {
            return "пара";
        }
        String trimmed = label.strip();
        boolean several = trimmed.contains("-") || trimmed.contains(",") || trimmed.contains("–");
        return trimmed.replace('-', '–') + (several ? " пари" : " пара");
    }

    private static String lesson(String discipline, String teacher) {
        List<String> parts = new ArrayList<>(2);
        if (notBlank(discipline)) {
            parts.add(discipline.strip());
        }
        if (notBlank(teacher)) {
            parts.add(teacher.strip());
        }
        return String.join(" · ", parts);
    }

    private static String boldLesson(String lesson) {
        return lesson.isEmpty() ? "" : b(lesson);
    }

    private static String plural(int count, String one, String few, String many) {
        return Plurals.of(count, one, few, many);
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
