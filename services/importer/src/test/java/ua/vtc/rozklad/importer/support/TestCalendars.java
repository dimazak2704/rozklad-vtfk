package ua.vtc.rozklad.importer.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.GroupItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.PeriodKindItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.RoomItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.SemesterItem;

/** Core's catalog as the academic calendar import sees it: the 56 groups of 2026/27, room 401, builtin kinds. */
public final class TestCalendars {

    public static final String T_SUSPEND = "{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]";
    public static final String T_DAYOFF = "{хто}: {назва_мала} з {від}р. по {до} р.";
    public static final String T_INFO = "{хто} – {назва} з {від}р. по {до} р.";
    public static final UUID ROOM_401 = UUID.fromString("00000000-0000-4000-8000-000000000501");
    public static final List<String> GROUPS = List.of(
            "1ЕА1", "1ЕА2", "1ВЕ", "1КІ1", "1КІ2", "1КІ3", "1ОП1", "1ОП2", "1РТ1", "1РТ2", "1ТК1", "1ТК2", "1М", "1ОБ",
            "1ОД", "1Ф",
            "2ЕА1", "2ЕА2", "2ВЕ", "2КІ1", "2КІ2", "2КІ3", "2ОП1", "2ОП2", "2РТ1", "2РТ2", "2ТК", "2М", "2ОБ", "2ОД", "2Ф",
            "3ЕА1", "3ЕА2", "3ВЕ", "3КІ1", "3КІ2", "3КІ3", "3ОП1", "3ОП2", "3РТ1", "3РТ2", "3ТК", "3ЕП", "3ОД", "3ОБ", "3Ф",
            "4ЕА1", "4ЕА2", "4ВЕ", "4КІ1", "4КІ2", "4КІ3", "4ОП1", "4ОП2", "4РТ1", "4РТ2");

    private TestCalendars() {
    }

    public static CatalogSnapshot college() {
        return college(GROUPS, kinds());
    }

    public static CatalogSnapshot college(List<String> groups, List<PeriodKindItem> kinds) {
        List<GroupItem> items = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            String code = groups.get(i);
            items.add(new GroupItem(new UUID(0x1000, i), code, Character.getNumericValue(code.charAt(0)), List.of(), true));
        }
        return new CatalogSnapshot(items, List.of(), List.of(), List.of(new RoomItem(ROOM_401, "401", "CLASSROOM")),
                List.of(new SemesterItem(new UUID(0x2000, 1), "I семестр 2026/2027", 1, LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 12, 30), true)), kinds);
    }

    /** The 15 builtin kinds of the shared contract §1.1. */
    public static List<PeriodKindItem> kinds() {
        return List.of(
                kind(1, "К", "Канікули", "DAY_OFF", T_DAYOFF),
                kind(2, "С", "Святкові дні", "DAY_OFF", T_DAYOFF),
                kind(3, "БЗ", "Без занять", "DAY_OFF", T_DAYOFF),
                kind(4, "НП", "Практика", "SUSPEND", T_SUSPEND),
                kind(5, "ВП", "Виробнича практика", "SUSPEND", T_SUSPEND),
                kind(6, "ПП", "Переддипломна практика", "SUSPEND", T_SUSPEND),
                kind(7, "Т", "Технологічна практика", "SUSPEND", T_SUSPEND),
                new PeriodKindItem(new UUID(0x3000, 8), "ЗУ", "Збори", "SUSPEND", null, List.of("З"), T_SUSPEND, true),
                kind(9, "НС", "Національний спротив", "SUSPEND", T_SUSPEND),
                kind(10, "І", "Сесія", "SUSPEND", T_SUSPEND),
                kind(11, "Д", "ДПА", "SUSPEND", T_SUSPEND),
                kind(12, "А", "Атестація", "SUSPEND", T_SUSPEND),
                kind(13, "ФН", "Форма навчання", "ONLINE", "{хто} з {від}р. по {до} {режим}."),
                kind(14, "Р", "Резерв навчального часу", "INFO", T_INFO),
                kind(15, "ВКР", "Виконання кваліфікаційної роботи", "INFO", T_INFO));
    }

    public static PeriodKindItem kind(int n, String code, String name, String effect, String template) {
        return new PeriodKindItem(new UUID(0x3000, n), code, name, effect, null, List.of(), template, true);
    }
}
