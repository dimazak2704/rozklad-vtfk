package ua.vtc.rozklad.importer.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.importer.job.ImportParseException;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.support.SeedFiles;

class AcademicCalendarParserTest {

    static final String REAL_FILE = "academic-calendar-2026-27.xlsx";

    static AcademicCalendarParser.Result real() {
        return AcademicCalendarParser.parse(SeedFiles.read(REAL_FILE), JobOptions.none());
    }

    @Test
    void realCalendarHas56GroupsAnd11CodesWithDatesFromTheTitleYear() {
        AcademicCalendarParser.Result result = real();
        AcademicCalendarDraft draft = result.draft();

        assertThat(draft.academicYear()).isEqualTo("2026/2027");
        assertThat(draft.sheet()).isEqualTo("2026-2027");
        assertThat(draft.groups()).hasSize(56);
        assertThat(draft.groups()).extracting(AcademicCalendarDraft.Group::code)
                .contains("1ЕА1", "4КІ1", "4ОП1", "4РТ2", "1М", "3ЕП").doesNotHaveDuplicates();
        assertThat(draft.codes()).extracting(AcademicCalendarDraft.Code::code)
                .containsExactlyInAnyOrder("І", "А", "ВП", "Д", "ЗУ", "К", "НП", "НС", "ПП", "Р", "С");
        assertThat(result.issues()).noneMatch(row -> row.severity() == Severity.ERROR || row.severity() == Severity.WARN);
    }

    @Test
    void groupRowKeepsRawCodeExcelRowTheoryWeeksAndExams() {
        AcademicCalendarDraft.Group group = group(real().draft(), "4КІ1");
        assertThat(group.raw()).isEqualTo("4КІ-1");
        assertThat(group.row()).isEqualTo(61);
        assertThat(group.theoryWeeks()).containsExactly(20, null);
        assertThat(group.exams()).containsExactly(4, null);
        AcademicCalendarDraft.Group first = group(real().draft(), "1ЕА1");
        assertThat(first.theoryWeeks()).containsExactly(16, 23);
        assertThat(first.exams()).containsExactly(0, 0);
    }

    @Test
    void nationalResistanceRunsEndOnFridayOfTheirLastWeek() {
        AcademicCalendarDraft draft = real().draft();
        assertThat(run(draft, "4КІ1", "НС")).satisfies(run -> {
            assertThat(run.startsOn()).isEqualTo(LocalDate.of(2026, 11, 16));
            assertThat(run.endsOn()).isEqualTo(LocalDate.of(2026, 11, 27));
            assertThat(run.weeks()).isEqualTo(2);
            assertThat(run.cells()).isEqualTo("T61:U61");
        });
        assertThat(run(draft, "4ОП2", "НС").startsOn()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(run(draft, "4ОП2", "НС").endsOn()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(run(draft, "4ВЕ", "НС").startsOn()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(run(draft, "4ВЕ", "НС").endsOn()).isEqualTo(LocalDate.of(2026, 11, 13));
        // зимові канікули: два тижні одним періодом, кінець — п'ятниця 08.01
        assertThat(run(draft, "1КІ1", "К").startsOn()).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(run(draft, "1КІ1", "К").endsOn()).isEqualTo(LocalDate.of(2027, 1, 8));
    }

    @Test
    void practiceTitleComesFromTheLegendByCellColour() {
        AcademicCalendarDraft draft = real().draft();
        List<AcademicCalendarDraft.Run> practices = draft.runs().stream()
                .filter(run -> run.groupCode().equals("2КІ1") && run.code().equals("НП")).toList();
        assertThat(practices).extracting(AcademicCalendarDraft.Run::title)
                .containsExactly("Навчальна радіомонтажна практика", "Навчальна практика");
        assertThat(draft.runs()).filteredOn(run -> run.groupCode().equals("3КІ1") && run.code().equals("НП"))
                .extracting(AcademicCalendarDraft.Run::title)
                .containsExactly("Практика з програмування", "Практика з мікропроцесорних систем");
        assertThat(run(draft, "4КІ1", "НС").title()).isNull();
        assertThat(draft.legend()).contains(
                new AcademicCalendarDraft.LegendEntry("НП", "#FF6600", "Навчальна радіомонтажна практика"),
                new AcademicCalendarDraft.LegendEntry("З", null, "Збори (ЗУ)"));
        assertThat(draft.legend()).extracting(AcademicCalendarDraft.LegendEntry::code)
                .contains("Р", "К", "Д", "А", "ВКР", "С", "ВП", "Т", "ПП", "НС");
    }

    @Test
    void academicYearOptionWinsOverTheTitle() {
        assertThatThrownBy(() -> AcademicCalendarParser.parse(SeedFiles.read(REAL_FILE), options(Map.of(
                "academicYear", "2027/2028"))))
                .isInstanceOf(ImportParseException.class)
                .hasMessageContaining("не припадає на понеділок");
    }

    @Test
    void missingSheetListsTheSheets() {
        assertThatThrownBy(() -> AcademicCalendarParser.parse(SeedFiles.read(REAL_FILE), options(Map.of("sheet", "Графік"))))
                .isInstanceOf(ImportParseException.class)
                .hasMessage("Аркуша «Графік» немає у файлі. Є: «2026-2027», «Лист1».");
    }

    // ───────────────────────── synthetic sheets ─────────────────────────

    @Test
    void adjacentEqualCodesMergeAndAGapOrAnotherCodeStartsANewRun() throws IOException {
        byte[] file = sheet(new String[][] {{"4КІ-1", "НС", "НС", "", "НС", "К", "К"}});
        AcademicCalendarDraft draft = AcademicCalendarParser.parse(file, JobOptions.none()).draft();

        assertThat(draft.runs()).extracting(run -> run.code() + " " + run.startsOn() + " " + run.endsOn() + " " + run.cells())
                .containsExactly(
                        "НС 2026-08-31 2026-09-11 D6:E6",
                        "НС 2026-09-21 2026-09-25 G6:G6",
                        "К 2026-09-28 2026-10-09 H6:I6");
        assertThat(draft.codes()).extracting(code -> code.code() + "=" + code.cells()).containsExactly("К=2", "НС=3");
    }

    @Test
    void latinLookalikesAndHyphensAreFolded() throws IOException {
        byte[] file = sheet(new String[][] {{"2KI-3", "HП", "НП"}, {"4ВЕ", "", "K"}});
        AcademicCalendarDraft draft = AcademicCalendarParser.parse(file, JobOptions.none()).draft();

        assertThat(draft.groups()).extracting(AcademicCalendarDraft.Group::code).containsExactly("2КІ3", "4ВЕ");
        assertThat(draft.runs()).extracting(run -> run.groupCode() + " " + run.code() + " " + run.weeks())
                .containsExactly("2КІ3 НП 2", "4ВЕ К 1");
    }

    @Test
    void unknownCodeIsKeptForTheReportAndNotACodeIsWarned() throws IOException {
        byte[] file = sheet(new String[][] {{"4КІ1", "Х", "1/2"}});
        AcademicCalendarParser.Result result = AcademicCalendarParser.parse(file, JobOptions.none());

        assertThat(result.draft().codes()).extracting(AcademicCalendarDraft.Code::code).containsExactly("Х");
        assertThat(result.issues()).anySatisfy(row -> {
            assertThat(row.severity()).isEqualTo(Severity.WARN);
            assertThat(row.location()).isEqualTo("2026-2027!E6");
            assertThat(row.message()).isEqualTo("«1/2» — не код графіка: тиждень пропущено.");
        });
    }

    @Test
    void duplicateGroupRowIsWarnedAndSkipped() throws IOException {
        byte[] file = sheet(new String[][] {{"4КІ1", "НС"}, {"4КІ-1", "К"}});
        AcademicCalendarParser.Result result = AcademicCalendarParser.parse(file, JobOptions.none());

        assertThat(result.draft().groups()).hasSize(1);
        assertThat(result.draft().runs()).extracting(AcademicCalendarDraft.Run::code).containsExactly("НС");
        assertThat(result.issues()).anyMatch(row -> row.message().startsWith("Група 4КІ1 є в графіку двічі"));
    }

    @Test
    void sheetWithoutGroupHeaderIsRejected() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Аркуш").createRow(0).createCell(0).setCellValue("Розклад");
            assertThatThrownBy(() -> AcademicCalendarParser.parse(bytes(workbook), JobOptions.none()))
                    .isInstanceOf(ImportParseException.class)
                    .hasMessageContaining("не знайдено графіка");
        }
    }

    @Test
    void weeksThatDoNotFollowEachOtherAreRejected() throws IOException {
        byte[] file = sheet(new String[][] {{"4КІ1", "НС"}}, new int[][] {{31, 6}, {7, 13}, {21, 27}, {28, 4}, {5, 11}});
        assertThatThrownBy(() -> AcademicCalendarParser.parse(file, JobOptions.none()))
                .isInstanceOf(ImportParseException.class)
                .hasMessage("Тижні графіка йдуть не підряд: у 2026-2027!F3 очікувався тиждень 14.09.2026–20.09.2026.");
    }

    @Test
    void yearIsRequiredWhenNeitherTitleNorSheetSaysIt() throws IOException {
        byte[] file = sheet("Аркуш", "Графік", new String[][] {{"4КІ1", "НС"}}, WEEKS);
        assertThatThrownBy(() -> AcademicCalendarParser.parse(file, JobOptions.none()))
                .isInstanceOf(ImportParseException.class)
                .hasMessageContaining("academicYear");
        assertThat(AcademicCalendarParser.parse(file, options(Map.of("academicYear", "2026/2027"))).draft().runs())
                .hasSize(1);
    }

    @Test
    void outOfRangeTheoryWeeksAreWarnedAndDropped() throws IOException {
        byte[] file = sheet(new String[][] {{"4КІ1", "НС"}});
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
            workbook.getSheetAt(0).getRow(5).createCell(1).setCellValue(60);
            AcademicCalendarParser.Result result = AcademicCalendarParser.parse(bytes(workbook), JobOptions.none());
            assertThat(result.draft().groups().getFirst().theoryWeeks()).containsExactly(null, 18);
            assertThat(result.issues()).anyMatch(row -> row.message().equals(
                    "Кількість тижнів теорії «60» — не ціле число від 1 до 53: не імпортується."));
        }
    }

    @Test
    void sentenceCaseLowersAllCapsOnly() {
        assertThat(AcademicCalendarParser.sentenceCase("НАВЧАЛЬНА РАДІОМОНТАЖНА ПРАКТИКА"))
                .isEqualTo("Навчальна радіомонтажна практика");
        assertThat(AcademicCalendarParser.sentenceCase("навчальна практика ПЗ")).isEqualTo("Навчальна практика ПЗ");
    }

    @Test
    void yearIsReadFromTitles() {
        assertThat(AcademicCalendarParser.year("на 2026 - 2027 навчальний рік")).contains("2026/2027");
        assertThat(AcademicCalendarParser.year("2026-2027")).contains("2026/2027");
        assertThat(AcademicCalendarParser.year("2026-2028")).isEmpty();
    }

    @Test
    void coloursAreReadAsHex() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFRow row = workbook.createSheet().createRow(0);
            XSSFCellStyle orange = workbook.createCellStyle();
            orange.setFillForegroundColor(new XSSFColor(new byte[] {(byte) 0xFF, 0x66, 0x00}));
            orange.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            row.createCell(0).setCellStyle(orange);
            XSSFCellStyle white = workbook.createCellStyle();
            white.setFillForegroundColor(new XSSFColor(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
            white.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            row.createCell(1).setCellStyle(white);
            assertThat(AcademicCalendarParser.colour(row.getCell(0))).isEqualTo("#FF6600");
            assertThat(AcademicCalendarParser.colour(row.getCell(1))).isNull();
            assertThat(AcademicCalendarParser.colour(row.createCell(2))).isNull();
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    static final int[][] WEEKS = {{31, 6}, {7, 13}, {14, 20}, {21, 27}, {28, 4}, {5, 11}, {12, 18}, {19, 25}};

    static AcademicCalendarDraft.Group group(AcademicCalendarDraft draft, String code) {
        return draft.groups().stream().filter(group -> group.code().equals(code)).findFirst().orElseThrow();
    }

    static AcademicCalendarDraft.Run run(AcademicCalendarDraft draft, String group, String code) {
        return draft.runs().stream().filter(run -> run.groupCode().equals(group) && run.code().equals(code))
                .findFirst().orElseThrow();
    }

    static JobOptions options(Map<String, Object> values) {
        return JobOptions.from(tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(values));
    }

    /** A calendar «на 2026 - 2027 навчальний рік» with 8 weeks from 31.08.2026; rows: group, codes of weeks 1… */
    static byte[] sheet(String[][] groups) throws IOException {
        return sheet(groups, WEEKS);
    }

    static byte[] sheet(String[][] groups, int[][] weeks) throws IOException {
        return sheet("2026-2027", "Робочий графік на 2026 - 2027 навчальний рік", groups, weeks);
    }

    /**
     * Layout like the college's file, smaller: A1 title; row 2 header (A «ГРУПА», B «К-сть тижн», C… months);
     * rows 3/4 first/last days of the weeks from column C; groups from row 6 (B/… theory weeks 18, 18).
     */
    static byte[] sheet(String name, String title, String[][] groups, int[][] weeks) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet(name);
            sheet.createRow(0).createCell(0).setCellValue(title);
            XSSFRow header = sheet.createRow(1);
            header.createCell(0).setCellValue("ГРУПА");
            header.createCell(1).setCellValue("К-сть тижн теор.навч");
            XSSFRow starts = sheet.createRow(2);
            XSSFRow ends = sheet.createRow(3);
            for (int i = 0; i < weeks.length; i++) {
                starts.createCell(3 + i).setCellValue(weeks[i][0]);
                ends.createCell(3 + i).setCellValue(weeks[i][1]);
            }
            sheet.createRow(4).createCell(0).setCellValue("№ тижня");
            for (int g = 0; g < groups.length; g++) {
                XSSFRow row = sheet.createRow(5 + g);
                row.createCell(0).setCellValue(groups[g][0]);
                row.createCell(1).setCellValue(18);
                row.createCell(2).setCellValue(18);
                String[] codes = Arrays.copyOfRange(groups[g], 1, groups[g].length);
                for (int i = 0; i < codes.length; i++) {
                    if (!codes[i].isEmpty()) {
                        row.createCell(3 + i).setCellValue(codes[i]);
                    }
                }
            }
            return bytes(workbook);
        }
    }

    static byte[] bytes(XSSFWorkbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }
}
