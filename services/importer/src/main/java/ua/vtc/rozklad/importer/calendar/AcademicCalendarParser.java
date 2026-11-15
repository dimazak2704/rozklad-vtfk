package ua.vtc.rozklad.importer.calendar;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFColor;
import ua.vtc.rozklad.importer.job.ExcelWorkbooks;
import ua.vtc.rozklad.importer.job.ImportParseException;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.GroupCode;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * Deterministic POI parser of the college's academic calendar. The structure is found by content, not by fixed
 * cells: the header cell «ГРУПА» gives the group column; below it the two rows of day numbers whose pairs are
 * 6 days apart are the weeks' first and last days (dates are rebuilt from the academic year: the first week is
 * the Monday nearest to 1 September with that day number, every next column is 7 days later); the header cells
 * with «тижн» and «іспит» left of the weeks give theory weeks and exams of semesters I and II; every row whose
 * group cell is a group code («4КІ-1» → «4КІ1») is a group; the code/description pairs below the groups are the
 * legend. Equal adjacent codes of a group form a run from Monday of its first week to Friday of its last. When
 * the legend has several lines for a code, the cell's fill colour picks the line («НП» in orange → «Навчальна
 * радіомонтажна практика»; an uncoloured cell → the uncoloured line).
 */
public final class AcademicCalendarParser {

    static final String DAMAGED = "Не вдалося прочитати файл Excel графіка освітнього процесу: він пошкоджений або має "
            + "інший формат. Збережіть його в Excel ще раз як .xlsx.";
    private static final long MAX_CELLS = 2_000_000;
    private static final int MAX_MERGED_REGIONS = 50_000;
    private static final int MAX_SHEETS = 30;
    private static final int MAX_GROUPS = 500;
    private static final int MIN_WEEKS = 4;
    private static final Pattern YEAR = Pattern.compile("(20\\d{2})\\s*[-–—/]\\s*(20\\d{2})");
    private static final Pattern CODE = Pattern.compile("^[\\p{L}]{1,10}$");
    private static final Pattern LEGEND_CODE = Pattern.compile("^[\\p{Lu}]{1,4}$");
    private static final Pattern DASHES = Pattern.compile("[-–—]");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private AcademicCalendarParser() {
    }

    public record Result(AcademicCalendarDraft draft, List<ReportRow> issues) {
    }

    /** Where the grid is: 0-based rows and columns. */
    private record Layout(int headerRow, int groupColumn, int startRow, int endRow, int firstWeek, int lastWeek,
                          int theoryColumn, int examsColumn) {
    }

    /** One week cell of a group: folded code and the fill colour key. */
    private record WeekCell(String code, String colour) {
    }

    public static Result parse(byte[] content, JobOptions options) {
        try (Workbook workbook = ExcelWorkbooks.open(content, DAMAGED)) {
            Issues issues = new Issues();
            Sheet sheet = sheet(workbook, options.sheet());
            Layout layout = layout(sheet);
            if (layout == null) {
                throw new ImportParseException("На аркуші «" + sheet.getSheetName() + "» не знайдено графіка: потрібні "
                        + "заголовок «ГРУПА» і під ним два рядки з днями початку й кінця тижнів.");
            }
            String academicYear = options.academicYear() != null ? options.academicYear() : academicYear(sheet, layout);
            if (academicYear == null) {
                throw new ImportParseException("Не вдалося визначити навчальний рік графіка: у заголовку немає «2026 - 2027». "
                        + "Завантажте файл ще раз із параметром academicYear, наприклад «2026/2027».");
            }
            List<LocalDate> mondays = mondays(sheet, layout, Integer.parseInt(academicYear.substring(0, 4)));
            Map<Long, CellRangeAddress> merges = merges(sheet);
            List<AcademicCalendarDraft.Group> groups = new ArrayList<>();
            Map<String, List<WeekCell>> weeks = new LinkedHashMap<>();
            Map<String, Integer> rows = new HashMap<>();
            int lastGroupRow = layout.endRow();
            for (int r = layout.endRow() + 1; r <= sheet.getLastRowNum(); r++) {
                String raw = plain(sheet, r, layout.groupColumn());
                if (raw.isEmpty()) {
                    continue;
                }
                Optional<GroupCode> code = GroupCode.parse(DASHES.matcher(raw).replaceAll(""));
                String location = location(sheet, r, layout.groupColumn());
                if (code.isEmpty()) {
                    if (raw.length() <= 10 && raw.chars().anyMatch(Character::isDigit)) {
                        issues.warn(RowType.CELL, raw, location, "«" + raw + "» не схоже на код групи: рядок пропущено.");
                    }
                    continue;
                }
                String canonical = code.get().code();
                if (rows.containsKey(canonical)) {
                    issues.warn(RowType.GROUP, raw, location, "Група " + canonical + " є в графіку двічі (рядки "
                            + (rows.get(canonical) + 1) + " і " + (r + 1) + "): узято перший рядок.");
                    continue;
                }
                if (groups.size() == MAX_GROUPS) {
                    throw new ImportParseException("У графіку понад " + MAX_GROUPS + " груп — це не схоже на графік коледжу.");
                }
                rows.put(canonical, r);
                lastGroupRow = r;
                groups.add(new AcademicCalendarDraft.Group(raw, canonical, r + 1,
                        semesterValues(sheet, r, layout.theoryColumn(), 1, 53, "тижнів теорії", issues),
                        semesterValues(sheet, r, layout.examsColumn(), 0, 20, "іспитів", issues)));
                List<WeekCell> cells = new ArrayList<>();
                for (int c = layout.firstWeek(); c <= layout.lastWeek(); c++) {
                    cells.add(weekCell(sheet, merges, r, c, issues));
                }
                weeks.put(canonical, cells);
            }
            if (groups.isEmpty()) {
                throw new ImportParseException("У графіку не знайдено жодної групи під заголовком «ГРУПА».");
            }
            List<AcademicCalendarDraft.LegendEntry> legend = new ArrayList<>();
            Map<String, Map<String, String>> colourTitles = legend(sheet, lastGroupRow + 1, legend);
            List<AcademicCalendarDraft.Run> runs = new ArrayList<>();
            Map<String, Integer> cellCounts = new TreeMap<>();
            for (AcademicCalendarDraft.Group group : groups) {
                List<WeekCell> cells = weeks.get(group.code());
                int row = group.row() - 1;
                int i = 0;
                while (i < cells.size()) {
                    WeekCell cell = cells.get(i);
                    if (cell == null) {
                        i++;
                        continue;
                    }
                    String title = title(colourTitles, cell);
                    int j = i;
                    while (j + 1 < cells.size() && cells.get(j + 1) != null && cells.get(j + 1).code().equals(cell.code())
                            && java.util.Objects.equals(title(colourTitles, cells.get(j + 1)), title)) {
                        j++;
                    }
                    cellCounts.merge(cell.code(), j - i + 1, Integer::sum);
                    String range = CellReference.convertNumToColString(layout.firstWeek() + i) + (row + 1) + ":"
                            + CellReference.convertNumToColString(layout.firstWeek() + j) + (row + 1);
                    runs.add(new AcademicCalendarDraft.Run(group.code(), cell.code(), title, mondays.get(i),
                            mondays.get(j).plusDays(4), j - i + 1, range));
                    i = j + 1;
                }
            }
            List<AcademicCalendarDraft.Code> codes = cellCounts.entrySet().stream()
                    .map(entry -> new AcademicCalendarDraft.Code(entry.getKey(), null, null, null, entry.getValue()))
                    .toList();
            issues.info(RowType.FILE, sheet.getSheetName(), null, "Графік " + academicYear + ": груп — " + groups.size()
                    + ", кодів — " + codes.size() + ", тижні з " + DAY.format(mondays.getFirst()) + " по "
                    + DAY.format(mondays.getLast().plusDays(6)) + ".");
            return new Result(new AcademicCalendarDraft(academicYear, sheet.getSheetName(), groups, codes, legend, runs,
                    List.of(), List.of(), null), issues.rows());
        } catch (IOException e) {
            throw new ImportParseException(DAMAGED, e);
        }
    }

    // ───────────────────────── structure ─────────────────────────

    private static Sheet sheet(Workbook workbook, String name) {
        if (name != null) {
            Sheet sheet = workbook.getSheet(name);
            if (sheet == null) {
                List<String> names = new ArrayList<>();
                workbook.forEach(each -> names.add("«" + each.getSheetName() + "»"));
                throw new ImportParseException("Аркуша «" + name + "» немає у файлі. Є: " + String.join(", ", names) + ".");
            }
            checkSize(sheet);
            return sheet;
        }
        int tried = 0;
        for (Sheet sheet : workbook) {
            if (++tried > MAX_SHEETS) {
                break;
            }
            if (tooLarge(sheet)) {
                continue;
            }
            if (layout(sheet) != null) {
                return sheet;
            }
        }
        throw new ImportParseException("У файлі не знайдено графіка освітнього процесу: потрібен аркуш із заголовком "
                + "«ГРУПА» і рядками дат тижнів.");
    }

    private static void checkSize(Sheet sheet) {
        if (tooLarge(sheet)) {
            throw new ImportParseException("Аркуш «" + sheet.getSheetName() + "» завеликий для графіка освітнього процесу: "
                    + "залиште в ньому лише таблицю графіка.");
        }
    }

    /** Bounded before any scan: a few kilobytes of XML can declare a cell at XFD1048576 (see SheetGrid). */
    private static boolean tooLarge(Sheet sheet) {
        int maxColumn = 0;
        for (Row row : sheet) {
            maxColumn = Math.max(maxColumn, row.getLastCellNum());
        }
        return (long) (sheet.getLastRowNum() + 1) * Math.max(1, maxColumn) > MAX_CELLS
                || sheet.getNumMergedRegions() > MAX_MERGED_REGIONS;
    }

    private static Layout layout(Sheet sheet) {
        int lastRow = Math.min(sheet.getLastRowNum(), 40);
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c < row.getLastCellNum(); c++) {
                if (!"група".equals(TextNormalizer.key(plain(sheet, r, c)))) {
                    continue;
                }
                Layout layout = weeks(sheet, r, c);
                if (layout != null) {
                    return layout;
                }
            }
        }
        return null;
    }

    /** The pair of rows below the header whose columns hold a week's first and last day for the most weeks. */
    private static Layout weeks(Sheet sheet, int header, int groupColumn) {
        int bestRow = -1;
        int bestFirst = -1;
        int bestLength = 0;
        for (int r = header + 1; r <= header + 8 && r < sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int run = 0;
            for (int c = groupColumn + 1; c <= row.getLastCellNum(); c++) {
                Integer start = day(sheet, r, c);
                Integer end = day(sheet, r + 1, c);
                boolean week = start != null && end != null && (end == start + 6 || end < start && end <= 6 && start >= 23);
                run = week ? run + 1 : 0;
                if (run > bestLength) {
                    bestLength = run;
                    bestRow = r;
                    bestFirst = c - run + 1;
                }
            }
        }
        if (bestLength < MIN_WEEKS) {
            return null;
        }
        int theory = -1;
        int exams = -1;
        for (int c = groupColumn + 1; c < bestFirst; c++) {
            String key = TextNormalizer.key(plain(sheet, header, c));
            if (theory < 0 && key.contains("тижн")) {
                theory = c;
            } else if (exams < 0 && key.contains("іспит")) {
                exams = c;
            }
        }
        return new Layout(header, groupColumn, bestRow, bestRow + 1, bestFirst, bestFirst + bestLength - 1, theory, exams);
    }

    private static String academicYear(Sheet sheet, Layout layout) {
        for (int r = 0; r < layout.headerRow(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c < row.getLastCellNum(); c++) {
                Optional<String> year = year(plain(sheet, r, c));
                if (year.isPresent()) {
                    return year.get();
                }
            }
        }
        return year(sheet.getSheetName()).orElse(null);
    }

    static Optional<String> year(String text) {
        Matcher matcher = YEAR.matcher(text);
        while (matcher.find()) {
            int first = Integer.parseInt(matcher.group(1));
            if (Integer.parseInt(matcher.group(2)) == first + 1) {
                return Optional.of(first + "/" + (first + 1));
            }
        }
        return Optional.empty();
    }

    /**
     * Monday of every week column: the first is the Monday nearest to 1 September of the year's first half whose
     * first and last days match the cells; the next ones follow by 7 days and must match their cells too.
     */
    private static List<LocalDate> mondays(Sheet sheet, Layout layout, int year) {
        int startDay = day(sheet, layout.startRow(), layout.firstWeek());
        int endDay = day(sheet, layout.endRow(), layout.firstWeek());
        LocalDate september = LocalDate.of(year, 9, 1);
        LocalDate first = null;
        for (int distance = 0; distance <= 40 && first == null; distance++) {
            for (LocalDate candidate : List.of(september.minusDays(distance), september.plusDays(distance))) {
                if (candidate.getDayOfWeek() == DayOfWeek.MONDAY && candidate.getDayOfMonth() == startDay
                        && candidate.plusDays(6).getDayOfMonth() == endDay) {
                    first = candidate;
                    break;
                }
            }
        }
        if (first == null) {
            throw new ImportParseException("Перший тиждень графіка (" + location(sheet, layout.startRow(), layout.firstWeek())
                    + ": " + startDay + "–" + endDay + ") не припадає на понеділок–неділю біля 1 вересня " + year
                    + " р. Перевірте навчальний рік (параметр academicYear).");
        }
        List<LocalDate> mondays = new ArrayList<>();
        for (int c = layout.firstWeek(); c <= layout.lastWeek(); c++) {
            LocalDate monday = first.plusWeeks(c - layout.firstWeek());
            if (day(sheet, layout.startRow(), c) != monday.getDayOfMonth()
                    || day(sheet, layout.endRow(), c) != monday.plusDays(6).getDayOfMonth()) {
                throw new ImportParseException("Тижні графіка йдуть не підряд: у " + location(sheet, layout.startRow(), c)
                        + " очікувався тиждень " + DAY.format(monday) + "–" + DAY.format(monday.plusDays(6)) + ".");
            }
            mondays.add(monday);
        }
        return mondays;
    }

    // ───────────────────────── cells ─────────────────────────

    private static List<Integer> semesterValues(Sheet sheet, int row, int column, int min, int max, String what,
                                                Issues issues) {
        if (column < 0) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (int c = column; c <= column + 1; c++) {
            String text = plain(sheet, row, c);
            Integer value = null;
            if (!text.isEmpty()) {
                try {
                    value = Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    value = null;
                }
                if (value == null || value < min || value > max) {
                    issues.warn(RowType.CELL, text, location(sheet, row, c), "Кількість " + what + " «" + text
                            + "» — не ціле число від " + min + " до " + max + ": не імпортується.");
                    value = null;
                }
            }
            values.add(value);
        }
        return values;
    }

    private static WeekCell weekCell(Sheet sheet, Map<Long, CellRangeAddress> merges, int row, int column, Issues issues) {
        CellRangeAddress region = merges.get(key(row, column));
        int r = region == null ? row : region.getFirstRow();
        int c = region == null ? column : region.getFirstColumn();
        String text = plain(sheet, r, c);
        if (text.isEmpty()) {
            return null;
        }
        String code = JobOptions.periodCode(text);
        if (!CODE.matcher(code).matches()) {
            issues.warn(RowType.CELL, text, location(sheet, row, column), "«" + text + "» — не код графіка: тиждень "
                    + "пропущено.");
            return null;
        }
        return new WeekCell(code, colour(cell(sheet, r, c)));
    }

    /** Legend below the groups: a short upper-case code with a description in one of the next three cells. */
    private static Map<String, Map<String, String>> legend(Sheet sheet, int fromRow,
                                                           List<AcademicCalendarDraft.LegendEntry> entries) {
        Map<String, Map<String, String>> byCode = new LinkedHashMap<>();
        for (int r = fromRow; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int c = 0;
            while (c < row.getLastCellNum()) {
                String text = plain(sheet, r, c);
                String code = JobOptions.periodCode(text);
                int next = c + 1;
                if (text.length() <= 4 && LEGEND_CODE.matcher(code).matches()) {
                    for (int d = c + 1; d <= c + 3; d++) {
                        String description = plain(sheet, r, d);
                        if (description.isEmpty()) {
                            continue;
                        }
                        if (description.length() >= 2 && description.chars().anyMatch(Character::isLetter)) {
                            String colour = colour(cell(sheet, r, c));
                            String title = sentenceCase(description);
                            entries.add(new AcademicCalendarDraft.LegendEntry(code,
                                    colour != null && colour.startsWith("#") ? colour : null, title));
                            byCode.computeIfAbsent(code, any -> new LinkedHashMap<>())
                                    .putIfAbsent(colour == null ? "" : colour, title);
                            // опис не є кодом наступного рядка легенди («ДПА»)
                            next = d + 1;
                        }
                        break;
                    }
                }
                c = next;
            }
        }
        // колір розрізняє лише коди з кількома рядками легенди (НП); решта бере назву з параметрів або виду
        byCode.values().removeIf(variants -> variants.size() < 2);
        return byCode;
    }

    private static String title(Map<String, Map<String, String>> colourTitles, WeekCell cell) {
        Map<String, String> variants = colourTitles.get(cell.code());
        if (variants == null) {
            return null;
        }
        String title = variants.get(cell.colour() == null ? "" : cell.colour());
        return title != null ? title : variants.get("");
    }

    static String sentenceCase(String text) {
        String clean = TextNormalizer.clean(text);
        boolean upper = clean.chars().filter(Character::isLetter).allMatch(Character::isUpperCase);
        String body = upper ? clean.toLowerCase(TextNormalizer.UK) : clean;
        if (body.isEmpty()) {
            return body;
        }
        int first = body.codePointAt(0);
        int length = Character.charCount(first);
        return body.substring(0, length).toUpperCase(TextNormalizer.UK) + body.substring(length);
    }

    /** Solid fill colour as {@code #RRGGBB} (white = none); a theme colour POI cannot resolve as «theme:N:tint». */
    static String colour(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellStyle style = cell.getCellStyle();
        if (style == null || style.getFillPattern() != FillPatternType.SOLID_FOREGROUND
                || !(style.getFillForegroundColorColor() instanceof XSSFColor color)) {
            return null;
        }
        byte[] rgb = color.getRGBWithTint();
        if (rgb == null) {
            rgb = color.getRGB();
        }
        if (rgb == null || rgb.length < 3) {
            return color.isThemed() ? "theme:" + color.getTheme() + ":" + color.getTint() : null;
        }
        String hex = String.format("#%02X%02X%02X", rgb[rgb.length - 3] & 0xFF, rgb[rgb.length - 2] & 0xFF,
                rgb[rgb.length - 1] & 0xFF);
        return "#FFFFFF".equals(hex) ? null : hex;
    }

    private static Integer day(Sheet sheet, int row, int column) {
        String text = plain(sheet, row, column);
        if (text.isEmpty() || text.length() > 2 || !text.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int value = Integer.parseInt(text);
        return value >= 1 && value <= 31 ? value : null;
    }

    private static Cell cell(Sheet sheet, int row, int column) {
        Row r = sheet.getRow(row);
        return r == null || column < 0 ? null : r.getCell(column);
    }

    /** The cell's own text (merges are not followed), numbers without «.0». */
    private static String plain(Sheet sheet, int row, int column) {
        Cell cell = cell(sheet, row, column);
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> TextNormalizer.clean(cell.getStringCellValue());
            case NUMERIC -> {
                double value = cell.getNumericCellValue();
                yield value == Math.rint(value) && Math.abs(value) < 1e9 ? String.valueOf((long) value)
                        : String.valueOf(value);
            }
            default -> "";
        };
    }

    /** Merged regions by every covered cell; the sheet's size was bounded already. */
    private static Map<Long, CellRangeAddress> merges(Sheet sheet) {
        Map<Long, CellRangeAddress> regions = new HashMap<>();
        long covered = 0;
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            int bottom = Math.min(region.getLastRow(), sheet.getLastRowNum());
            for (int r = Math.max(0, region.getFirstRow()); r <= bottom; r++) {
                for (int c = Math.max(0, region.getFirstColumn()); c <= Math.min(region.getLastColumn(), 16_383); c++) {
                    if (++covered > MAX_CELLS) {
                        throw new ImportParseException("Аркуш «" + sheet.getSheetName() + "» має забагато об'єднаних "
                                + "клітинок для графіка освітнього процесу.");
                    }
                    regions.put(key(r, c), region);
                }
            }
        }
        return regions;
    }

    private static String location(Sheet sheet, int row, int column) {
        return sheet.getSheetName() + "!" + new CellReference(row, column).formatAsString(false);
    }

    private static long key(int row, int column) {
        return ((long) row << 20) | column;
    }
}
