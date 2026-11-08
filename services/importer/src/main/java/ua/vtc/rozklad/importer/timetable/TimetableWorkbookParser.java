package ua.vtc.rozklad.importer.timetable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.importer.job.ExcelWorkbooks;
import ua.vtc.rozklad.importer.job.ImportParseException;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * TIMETABLE_XLSX: parses the college workbook into a {@link TimetableDraft} plus report rows. The
 * sheet is the one requested, else «Розклад друк», else the first sheet with group headers.
 */
@Component
public class TimetableWorkbookParser {

    public static final String DEFAULT_SHEET = "Розклад друк";
    static final String DAMAGED = "Файл Excel пошкоджений або має інший формат — його не вдалося прочитати. Відкрийте "
            + "його в Excel, збережіть ще раз як .xlsx і завантажте знову.";

    public record Result(TimetableDraft draft, List<ReportRow> issues) {
    }

    public Result parse(byte[] content, String requestedSheet) {
        Issues issues = new Issues();
        try (Workbook workbook = ExcelWorkbooks.open(content, DAMAGED)) {
            Sheet sheet = chooseSheet(workbook, requestedSheet);
            SheetParse raw = new TimetableSheetParser(new SheetGrid(sheet), issues).parse();
            List<TimetableDraft.BellProfile> bells = new BellSheetParser(issues).parse(workbook);
            if (bells.isEmpty()) {
                issues.info(RowType.BELLS, null, null, "Аркушів із розкладом дзвінків у файлі не знайдено.");
            }
            TimetableDraft draft = new TimetableAssembler(issues).assemble(sheet.getSheetName(), raw, bells);
            return new Result(draft, issues.rows());
        } catch (IOException e) {
            // закриття книги в пам'яті нічого не пише; помилку тут не очікуємо
            throw new ImportParseException(DAMAGED, e);
        }
    }

    private static Sheet chooseSheet(Workbook workbook, String requested) {
        if (requested != null && !requested.isBlank()) {
            Sheet sheet = findSheet(workbook, requested);
            if (sheet == null) {
                throw new ImportParseException("Аркуш «" + requested + "» не знайдено. Аркуші файлу: " + sheetNames(workbook) + ".");
            }
            return sheet;
        }
        Sheet preferred = findSheet(workbook, DEFAULT_SHEET);
        if (preferred != null) {
            return preferred;
        }
        for (Sheet sheet : workbook) {
            Issues probe = new Issues();
            if (!new TimetableSheetParser(new SheetGrid(sheet), probe).parse().groups().isEmpty()) {
                return sheet;
            }
        }
        throw new ImportParseException("У файлі немає аркуша з розкладом груп. Аркуші файлу: " + sheetNames(workbook) + ".");
    }

    private static Sheet findSheet(Workbook workbook, String name) {
        String key = TextNormalizer.key(name);
        for (Sheet sheet : workbook) {
            if (TextNormalizer.key(sheet.getSheetName()).equals(key)) {
                return sheet;
            }
        }
        return null;
    }

    private static String sheetNames(Workbook workbook) {
        List<String> names = new ArrayList<>();
        workbook.forEach(sheet -> names.add("«" + sheet.getSheetName() + "»"));
        return String.join(", ", names);
    }
}
