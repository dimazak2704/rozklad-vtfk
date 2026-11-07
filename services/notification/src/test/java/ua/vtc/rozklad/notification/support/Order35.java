package ua.vtc.rozklad.notification.support;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.contracts.events.OrderPayload.AffectedGroup;
import ua.vtc.rozklad.contracts.events.OrderPayload.AffectedTeacher;
import ua.vtc.rozklad.contracts.events.OrderPayload.Item;

/** Order №35 of 19.10.2026 as on the photo {@code seed/orders/order-035-2026-10-19.jpg}. */
public final class Order35 {

    public static final UUID ORDER_ID = UUID.fromString("0190f0a0-0000-7000-8000-000000000035");
    public static final LocalDate DATE = LocalDate.of(2026, 10, 19);
    public static final UUID GROUP_4KI1 = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");
    public static final UUID GROUP_4KI2 = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8e");
    public static final UUID GROUP_1TK1 = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8f");
    public static final UUID TEACHER_KRENTSIN = UUID.fromString("2b3c4d5e-6f7a-4b2c-9d3e-4f5a6b7c8d9e");
    public static final UUID TEACHER_NEPYIVODA = UUID.fromString("3c4d5e6f-7a8b-4c3d-8e4f-5a6b7c8d9e0f");
    public static final UUID TEACHER_KRYSAK = UUID.fromString("3c4d5e6f-7a8b-4c3d-8e4f-5a6b7c8d9e10");
    public static final UUID CHANGE_4KI1 = UUID.fromString("7a8b9c0d-1e2f-4a7b-8c8d-9e0f1a2b3c4d");

    public static final List<String> NOTES = List.of(
            "2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.",
            "4ОП2 - Основи національного спротиву з 05.10.26р. по 16.10.26р.  401- аудиторія.",
            "3 курс групи 3Ф,3ОД, 3ЕП, 3ОБ, 3РТ,3ТК,3ЕА,3ВЕ,3ОП з 1.09.26р. по 31.10.26.– Навчаються за "
                    + "розкладом дистанційно.",
            "Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.",
            "4ЕА,4ВЕ,4РТ,4ОП навчаються очно з 01.09.26 по 31.10.26 р.");

    private Order35() {
    }

    public static List<Item> items() {
        return List.of(
                row("1ТК1", "1-4", OrderRowType.BLOCK, "ЗУ", "Ємельяненко", null, null, null, "204", null),
                row("1ОП2", "4", OrderRowType.ADDITIONAL, null, null, null, "БЖ", "Погосян", "403", null),
                row("1ТК1", "1", OrderRowType.CANCELLATION, "Ін. мова", "Крисак Л. (п.)", "Відп.", null, null, null,
                        null),
                row("1КІ2", "2", OrderRowType.REPLACEMENT, "Ін. мова", "Крисак Л.", "Відп.", "Ін.мова", "Прокопчук",
                        "110", null),
                row("1М", "3", OrderRowType.REPLACEMENT, "Ін. мова", "Крисак Л.", "Відп.", "Ін.мова", "Прокопчук",
                        "110", null),
                row("1ЕА2", "3", OrderRowType.REPLACEMENT, "ЕТ", "Масловата", "Зв.зам.", "Культ.", "Шароварська",
                        "405", null),
                row("2ОД", "4", OrderRowType.ADDITIONAL, null, null, null, "ІКТ", "Вернигора", "309", null),
                row("2ОД", "6", OrderRowType.REPLACEMENT, "ІМД", "Масловата", "Зв.зам.", "ОД", "Яцуба", "308", null),
                row("2ОБ", "7", OrderRowType.REPLACEMENT, "ЕіФБ", "Охота О.", "Зв.зам.", "ІКТ", "Вернигора", "304",
                        null),
                row("2ОБ", "8", OrderRowType.REPLACEMENT, "ІМД", "Масловата", "Зв.зам.", "ТБО", "Фабіянська", "409",
                        null),
                row("2М", "8", OrderRowType.REPLACEMENT, "ЕіФБ", "Охота О.", "Зв.зам.", "Укр.літ.", "Кучинська",
                        "303", null),
                row("2М", "5", OrderRowType.REPLACEMENT, "ІМД", "Масловата", "Зв.зам.", "Вс.іст.", "Балунь", "409",
                        null),
                row("2Ф", "7", OrderRowType.REPLACEMENT, "ІМД", "Масловата", "Зв.зам.", "ЕіФБ", "Кукурудзяк", "408",
                        null),
                row("3Ф", "4", OrderRowType.CANCELLATION, "Ін.мова", "Крисак Л. (п.)", "Відп.", null, null, null, null),
                row("4КІ1", "2", OrderRowType.REPLACEMENT, "СТП", "Кренцін", "Зв.зам.", "НДКС", "Непийвода", "6",
                        CHANGE_4KI1),
                row("4КІ2", "4", OrderRowType.REPLACEMENT, "СТП", "Кренцін", "Зв.зам.", "ОПЦЗ", "Ганза", "6", null));
    }

    public static OrderPayload payload() {
        return payload(OrderStatus.PUBLISHED, 35);
    }

    public static OrderPayload payload(OrderStatus status, Integer number) {
        return new OrderPayload(ORDER_ID, number, DATE, OrderKind.ORDER, null, status,
                "По коледжу про деякі зміни в розкладі занять", "ПОНЕДІЛОК", "ЗНАМЕННИК", items(), List.of(), NOTES,
                "Заступник директора з НР", "Микола ПОЛІЩУК", "I семестр 2026/2027",
                List.of(new AffectedGroup(GROUP_4KI1, "4КІ1"), new AffectedGroup(GROUP_4KI2, "4КІ2"),
                        new AffectedGroup(GROUP_1TK1, "1ТК1")),
                List.of(new AffectedTeacher(TEACHER_KRENTSIN, "Кренцін"),
                        new AffectedTeacher(TEACHER_NEPYIVODA, "Непийвода"),
                        new AffectedTeacher(TEACHER_KRYSAK, "Крисак Л.")));
    }

    public static Item row(String group, String pair, OrderRowType type, String fromDiscipline, String fromTeacher,
                           String reason, String toDiscipline, String toTeacher, String room, UUID changeId) {
        return new Item(UUID.nameUUIDFromBytes((group + pair + type).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                group, pair, type, fromDiscipline, fromTeacher, reason, toDiscipline, toTeacher, room, changeId);
    }
}
