package ua.vtc.rozklad.importer.support;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.DisciplineItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.GroupItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.RoomItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.SemesterItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.TeacherItem;

/** A small core catalog for tests: fixed ids so assertions can refer to them. */
public final class TestCatalogs {

    public static final UUID SEMESTER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final UUID KRENTSIN = UUID.fromString("00000000-0000-4000-8000-000000000101");
    public static final UUID NEPYIVODA = UUID.fromString("00000000-0000-4000-8000-000000000102");
    public static final UUID KHARCHENKO_O = UUID.fromString("00000000-0000-4000-8000-000000000103");
    public static final UUID KHARCHENKO_G = UUID.fromString("00000000-0000-4000-8000-000000000104");
    public static final UUID STETSIO = UUID.fromString("00000000-0000-4000-8000-000000000105");
    public static final UUID STP = UUID.fromString("00000000-0000-4000-8000-000000000201");
    public static final UUID NDEKSM = UUID.fromString("00000000-0000-4000-8000-000000000202");
    public static final UUID LANGUAGE = UUID.fromString("00000000-0000-4000-8000-000000000203");
    public static final UUID GROUP_4KI1 = UUID.fromString("00000000-0000-4000-8000-000000000301");
    public static final UUID GROUP_4KI2 = UUID.fromString("00000000-0000-4000-8000-000000000302");
    public static final UUID ROOM_308 = UUID.fromString("00000000-0000-4000-8000-000000000401");
    public static final UUID GYM = UUID.fromString("00000000-0000-4000-8000-000000000402");

    private TestCatalogs() {
    }

    /**
     * Кренцін and Непийвода exist (display names only in the Excel), Харченко О./Г. exist, and
     * «Стецьо В.В.» is close to the Excel's «Стець» (a possible duplicate that needs a decision).
     */
    public static CatalogSnapshot college() {
        return new CatalogSnapshot(
                List.of(new GroupItem(GROUP_4KI1, "4КІ1", 4, List.of(1, 2)), new GroupItem(GROUP_4KI2, "4КІ2", 4, List.of())),
                List.of(new TeacherItem(KRENTSIN, "Кренцін М.Д.", "Кренцін", "Кренцін", List.of()),
                        new TeacherItem(NEPYIVODA, "Непийвода М.В.", "Непийвода", "Непийвода", List.of("Непийвода М.")),
                        new TeacherItem(KHARCHENKO_O, "Харченко О.А.", "Харченко О.", "Харченко", List.of()),
                        new TeacherItem(KHARCHENKO_G, "Харченко Г.Ю.", "Харченко Г.", "Харченко", List.of()),
                        new TeacherItem(STETSIO, "Стецьо В.В.", "Стецьо", "Стецьо", List.of())),
                List.of(new DisciplineItem(STP, "СТП", "Сучасні технології проєктування", List.of()),
                        new DisciplineItem(NDEKSM, "НДЕКСМ",
                                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", List.of("НДКС")),
                        new DisciplineItem(LANGUAGE, "Ін.мова", "Іноземна мова", List.of())),
                List.of(new RoomItem(ROOM_308, "308", "CLASSROOM"), new RoomItem(GYM, "с.з.", "GYM")),
                List.of(new SemesterItem(SEMESTER, "I семестр 2026/2027", 1, LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 12, 30), true)));
    }
}
