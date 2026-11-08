package ua.vtc.rozklad.importer.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.importer.job.ImportParseException;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.support.SeedFiles;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.Group;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.Lesson;
import ua.vtc.rozklad.importer.timetable.TimetableDraft.Teacher;

/** Parses the real college workbook {@code seed/schedule-2025-26.xlsx} (sheet «Розклад друк»). */
class TimetableWorkbookParserTest {

    private static TimetableWorkbookParser.Result result;
    private static TimetableDraft draft;

    @BeforeAll
    static void parseRealWorkbook() {
        result = new TimetableWorkbookParser().parse(SeedFiles.read("schedule-2025-26.xlsx"), null);
        draft = result.draft();
    }

    @Test
    void readsPrintSheetWithGroupsPerCourse() {
        assertThat(draft.sheet()).isEqualTo("Розклад друк");
        Map<Integer, Long> perCourse = draft.groups().stream()
                .collect(Collectors.groupingBy(Group::course, Collectors.counting()));
        assertThat(perCourse).containsExactlyInAnyOrderEntriesOf(Map.of(1, 16L, 2, 15L, 3, 15L, 4, 10L));
        assertThat(draft.groups()).extracting(Group::code)
                .filteredOn(code -> code.startsWith("4"))
                .containsExactly("4ЕА1", "4ЕА2", "4ВЕ", "4РТ1", "4РТ2", "4КІ1", "4КІ2", "4КІ3", "4ОП1", "4ОП2");
        assertThat(draft.semesterLabel()).contains("семестр");
    }

    @Test
    void fixesTyposInGroupCodes() {
        Map<String, Group> groups = groupsByCode();
        assertThat(groups.get("1КІ1").raw()).isEqualTo("ІКІ1");
        assertThat(groups.get("1ТК1").raw()).isEqualTo("1ТК 1");
        assertThat(groups.get("1ЕА1").raw()).isEqualTo("1ЕА1");
        assertThat(groups).doesNotContainKeys("ІКІ1", "1ТК 1");
        assertThat(result.issues()).anyMatch(row -> row.raw() != null && row.raw().equals("ІКІ1")
                && row.message().contains("1КІ1"));
    }

    @Test
    void readsHomeRoomsAndCurators() {
        Group group = groupsByCode().get("4КІ1");
        assertThat(group.homeRoom()).isEqualTo("308");
        assertThat(group.curator()).isEqualTo("Голєва Т.В.");
        assertThat(group.specialtyLetters()).isEqualTo("КІ");
        assertThat(group.number()).isEqualTo(1);
        assertThat(groupsByCode().get("1ВЕ").number()).isNull();
        assertThat(groupsByCode().get("1ОБ").curator()).isEqualTo("Шароварська С.П.");
        assertThat(groupsByCode().get("1ОБ").actingCurator()).isEqualTo("Погосян Т.В.");
    }

    @Test
    void firstCourseTk1MondayFirstPairIsSplitIntoLanguageSubgroups() {
        List<Lesson> slot = lessons("1ТК1", 1, 1);
        assertThat(slot).extracting(Lesson::subgroup, Lesson::parity, Lesson::discipline, Lesson::teacher, Lesson::room)
                .containsExactlyInAnyOrder(
                        tuple(1, Parity.BOTH, "Ін.мова", "Крисак Л.В.", "110"),
                        tuple(2, Parity.BOTH, "Ін.мова", "Можаровська О.Е.", "12"));
    }

    @Test
    void mergedCellsMeanEveryWeek() {
        assertThat(lessons("1ЕА1", 1, 1)).singleElement()
                .extracting(Lesson::parity, Lesson::subgroup, Lesson::discipline, Lesson::teacher, Lesson::room)
                .containsExactly(Parity.BOTH, 0, "Матем.", "Вихристюк Ю.А.", "307");
    }

    @Test
    void numeratorOnlyAndDenominatorOnlyCells() {
        // 1РТ1, понеділок, 4 пара: заповнено лише верхній рядок
        assertThat(lessons("1РТ1", 1, 4)).singleElement()
                .extracting(Lesson::parity, Lesson::discipline, Lesson::room)
                .containsExactly(Parity.NUMERATOR, "ФК", "с.з.");
        // 1ВЕ, вівторок, 4 пара: лише нижній рядок
        assertThat(lessons("1ВЕ", 2, 4)).singleElement()
                .extracting(Lesson::parity, Lesson::discipline, Lesson::room)
                .containsExactly(Parity.DENOMINATOR, "ФіА", "408");
        // 1ЕА1, п'ятниця, 2 пара: різні дисципліни у двох рядках — чергування
        assertThat(lessons("1ЕА1", 5, 2)).extracting(Lesson::parity, Lesson::discipline)
                .containsExactlyInAnyOrder(tuple(Parity.NUMERATOR, "ФК"), tuple(Parity.DENOMINATOR, "Вс.іст."));
    }

    @Test
    void secondCourseIsSecondShiftWithPairsFiveToEight() {
        List<Group> second = draft.groups().stream().filter(group -> group.course() == 2).toList();
        assertThat(second).allMatch(group -> group.shift() == 2);
        assertThat(draft.groups().stream().filter(group -> group.course() != 2)).allMatch(group -> group.shift() == 1);
        List<Lesson> secondCourseLessons = draft.lessons().stream()
                .filter(lesson -> lesson.groupCode().startsWith("2")).toList();
        assertThat(secondCourseLessons).isNotEmpty()
                .allMatch(lesson -> lesson.weekday() == 5 ? lesson.pair() >= 4 && lesson.pair() <= 6 : lesson.pair() >= 5);
        assertThat(secondCourseLessons).extracting(Lesson::pair).contains(5, 6, 7, 8);
    }

    @Test
    void slashInOneCellMeansSubgroupsWithTheirRooms() {
        // 3ЕА1, понеділок, 3 пара: чисельник — ФК; знаменник — «Гарболінська/Марцинюк Т.» і «9/20» в одній клітинці
        assertThat(lessons("3ЕА1", 1, 3))
                .extracting(Lesson::parity, Lesson::subgroup, Lesson::discipline, Lesson::teacher, Lesson::room)
                .containsExactlyInAnyOrder(
                        tuple(Parity.NUMERATOR, 0, "ФК", "Феліксов Ю.Л.", "с.з."),
                        tuple(Parity.DENOMINATOR, 1, "Ін.мова", "Гарболінська", "9"),
                        tuple(Parity.DENOMINATOR, 2, "Ін.мова", "Марцинюк Т.Ю.", "20"));
    }

    @Test
    void subgroupMarkerMakesOneSubgroupLesson() {
        // 1ОП1, середа, 1 пара: «Харченко О.(п)»
        assertThat(lessons("1ОП1", 3, 1)).singleElement()
                .extracting(Lesson::subgroup, Lesson::teacher)
                .containsExactly(1, "Харченко О.А.");
    }

    @Test
    void consolidatesTeacherTyposAndKeepsNamesakesApart() {
        Map<String, Teacher> teachers = draft.teachers().stream()
                .collect(Collectors.toMap(Teacher::shortName, Function.identity()));
        assertThat(teachers.get("Гарболінська").variants()).contains("Граболінська");
        assertThat(teachers.get("Герасименко").variants()).contains("Герасменко");
        assertThat(teachers.get("Харченко О.А.").variants()).contains("Харченко О.", "Хорченко О.");
        assertThat(teachers.get("Харченко Г.Ю.").variants()).contains("Харченко Г.", "Харченко Г.Ю.");
        assertThat(teachers).containsKeys("Овчар Ін.М.", "Овчар Ів.М.", "Поліщук Л.І.", "Поліщук М.",
                "Шидловська Т.І.", "Шидловський", "Марценюк Н.М.", "Марцинюк Т.Ю.");
        assertThat(teachers.get("Овчар Ін.М.").displayName()).isEqualTo("Овчар Ін.");
        assertThat(teachers.get("Кренцін").displayName()).isEqualTo("Кренцін");
        assertThat(teachers.keySet()).doesNotContain("Граболінська", "Герасменко", "Хорченко О.", "Шидлорвська Т.І.");
        assertThat(draft.lessons()).extracting(Lesson::teacher).doesNotContain("Граболінська", "Герасменко");
        assertThat(result.issues()).anyMatch(row -> row.severity() == Severity.WARN
                && row.message().contains("Граболінська") && row.message().contains("Гарболінська"));
    }

    @Test
    void consolidatesDisciplineSpellings() {
        assertThat(draft.disciplines()).extracting(TimetableDraft.Discipline::shortName)
                .contains("Ін.мова", "Укр.мова", "Вс.іст.", "ФК", "Матем.", "Вс.спц.", "Вст.спц.", "ВСТР", "ВСТРС")
                .doesNotContain("Ін.мова.", "Укр.мова.", "Фк", "Матет.", "Вм.іст.", "Вс.");
        TimetableDraft.Discipline language = draft.disciplines().stream()
                .filter(discipline -> discipline.shortName().equals("Ін.мова")).findFirst().orElseThrow();
        assertThat(language.name()).isEqualTo("Іноземна мова");
        assertThat(language.needsReview()).isFalse();
        assertThat(draft.disciplines()).filteredOn(discipline -> discipline.shortName().equals("СПТС"))
                .singleElement().satisfies(discipline -> {
                    assertThat(discipline.name()).isEqualTo("СПТС");
                    assertThat(discipline.needsReview()).isTrue();
                });
    }

    @Test
    void canonicalRoomsIncludingSportsHall() {
        assertThat(draft.rooms()).filteredOn(room -> room.name().equals("с.з."))
                .singleElement().satisfies(room -> {
                    assertThat(room.type()).isEqualTo(RoomType.GYM);
                    assertThat(room.maxParallel()).isGreaterThanOrEqualTo(3);
                    assertThat(room.variants()).contains("с.з.", "с.з", "С.З.");
                });
        assertThat(draft.rooms()).extracting(TimetableDraft.Room::name).contains("с.п.", "307", "6")
                .doesNotContain("с.з", "С.З.", "110/12");
    }

    @Test
    void readsBellProfilesFromBellSheets() {
        assertThat(draft.bellProfiles()).extracting(TimetableDraft.BellProfile::name)
                .containsExactlyInAnyOrder("2 зміни, 60 хв", "2 зміни, 50 хв", "1 зміна, 80 хв");
        TimetableDraft.BellProfile sixty = draft.bellProfiles().stream()
                .filter(profile -> profile.name().equals("2 зміни, 60 хв")).findFirst().orElseThrow();
        assertThat(sixty.slots()).filteredOn(slot -> slot.weekday() == 1).hasSize(8)
                .extracting(TimetableDraft.BellSlot::pair, TimetableDraft.BellSlot::startsAt, TimetableDraft.BellSlot::endsAt)
                .contains(tuple(1, LocalTime.of(8, 30), LocalTime.of(9, 30)), tuple(8, LocalTime.of(17, 10), LocalTime.of(18, 10)));
        assertThat(sixty.slots()).filteredOn(slot -> slot.weekday() == 5).hasSize(6)
                .extracting(TimetableDraft.BellSlot::pair, TimetableDraft.BellSlot::startsAt)
                .contains(tuple(4, LocalTime.of(12, 20)), tuple(6, LocalTime.of(14, 50)));
        TimetableDraft.BellProfile eighty = draft.bellProfiles().stream()
                .filter(profile -> profile.name().equals("1 зміна, 80 хв")).findFirst().orElseThrow();
        assertThat(eighty.slots()).hasSize(20).filteredOn(slot -> slot.pair() == 3)
                .allMatch(slot -> slot.startsAt().equals(LocalTime.of(12, 0)));
    }

    @Test
    void reportsNoParseErrorsOnTheRealFile() {
        assertThat(result.issues()).filteredOn(row -> row.severity() == Severity.ERROR).isEmpty();
        assertThat(draft.lessons()).hasSizeGreaterThan(1000);
    }

    @Test
    void unknownSheetIsReportedWithAvailableSheets() {
        assertThatThrownBy(() -> new TimetableWorkbookParser().parse(SeedFiles.read("schedule-2025-26.xlsx"), "Нема"))
                .isInstanceOf(ImportParseException.class)
                .hasMessageContaining("«Нема»").hasMessageContaining("«Розклад друк»");
    }

    @Test
    void garbageIsNotAWorkbook() {
        assertThatThrownBy(() -> new TimetableWorkbookParser().parse("не excel".getBytes(), null))
                .isInstanceOf(ImportParseException.class);
    }

    /** r4-ai-imports-57: a cut sheet XML is a damaged file (plain message), not an internal error. */
    @Test
    void damagedSheetIsADamagedFile() throws Exception {
        byte[] original = SeedFiles.read("schedule-2025-26.xlsx");
        java.io.ByteArrayOutputStream damaged = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(original));
             java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(damaged)) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (entry.getName().startsWith("xl/worksheets/sheet")) {
                    data = java.util.Arrays.copyOf(data, data.length / 2);
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                out.write(data);
                out.closeEntry();
            }
        }
        assertThat(ua.vtc.rozklad.importer.job.FileTypeSniffer.detect(damaged.toByteArray()))
                .contains(ua.vtc.rozklad.importer.job.FileType.XLSX);

        assertThatThrownBy(() -> new TimetableWorkbookParser().parse(damaged.toByteArray(), null))
                .isInstanceOf(ImportParseException.class)
                .hasMessage(TimetableWorkbookParser.DAMAGED);
        // обрізаний посередині zip — той самий тип і те саме «пошкоджено» під час розбору
        byte[] truncated = java.util.Arrays.copyOf(original, original.length / 2);
        assertThat(ua.vtc.rozklad.importer.job.FileTypeSniffer.detect(truncated))
                .contains(ua.vtc.rozklad.importer.job.FileType.XLSX);
        assertThatThrownBy(() -> new TimetableWorkbookParser().parse(truncated, null))
                .isInstanceOf(ImportParseException.class)
                .hasMessage(TimetableWorkbookParser.DAMAGED);
    }

    private static Map<String, Group> groupsByCode() {
        return draft.groups().stream().collect(Collectors.toMap(Group::code, Function.identity()));
    }

    private static List<Lesson> lessons(String group, int weekday, int pair) {
        return draft.lessons().stream()
                .filter(lesson -> lesson.groupCode().equals(group) && lesson.weekday() == weekday && lesson.pair() == pair)
                .toList();
    }

    @SuppressWarnings("unused")
    private static List<ReportRow> rows() {
        return result.issues();
    }
}
