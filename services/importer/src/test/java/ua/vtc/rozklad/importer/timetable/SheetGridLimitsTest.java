package ua.vtc.rozklad.importer.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import ua.vtc.rozklad.importer.job.ImportParseException;

/**
 * A few kilobytes of XLSX must not hold an import worker or exhaust the importer's heap: a merge over the whole
 * sheet, a cell in the far corner, piles of overlapping merges.
 */
@Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
class SheetGridLimitsTest {

    @Test
    void aMergeOverTheWholeSheetIsIndexedOnlyWhereThereIsData() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(TimetableWorkbookParser.DEFAULT_SHEET);
            sheet.createRow(0).createCell(0).setCellValue("Розклад");
            sheet.createRow(3).createCell(4).setCellValue("x");
            // A1:XFD100000 — 1.6e9 клітинок (на весь аркуш POI сам не додасть: лічильник int переповнюється);
            // раніше кожна потрапляла в HashMap
            sheet.addMergedRegionUnsafe(new CellRangeAddress(0, 99_999, 0, 16_383));

            SheetGrid grid = new SheetGrid(sheet);

            assertThat(grid.region(3, 4)).isNotNull();
            assertThat(grid.text(3, 4)).isEmpty();
            assertThat(grid.text(0, 0)).isEqualTo("Розклад");
            assertThat(grid.spansRows(0, 3, 0)).isTrue();
        }
    }

    @Test
    void cellsInTheFarCornersAreRefusedBeforeAnyScan() throws IOException {
        byte[] file;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(TimetableWorkbookParser.DEFAULT_SHEET);
            sheet.createRow(0).createCell(0).setCellValue("Розклад");
            sheet.createRow(1_048_575).createCell(16_383).setCellValue("кут");
            file = bytes(workbook);
        }

        assertThatThrownBy(() -> new TimetableWorkbookParser().parse(file, null))
                .isInstanceOf(ImportParseException.class)
                .hasMessageStartingWith("Аркуш «Розклад друк» завеликий для розкладу");
    }

    @Test
    void overlappingMergesBeyondTheLimitAreRefused() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(TimetableWorkbookParser.DEFAULT_SHEET);
            sheet.createRow(299).createCell(299).setCellValue("x");
            for (int i = 0; i < 10; i++) {
                sheet.addMergedRegionUnsafe(new CellRangeAddress(0, 299, 0, 299));
            }

            assertThatThrownBy(() -> new SheetGrid(sheet)).isInstanceOf(SheetTooLargeException.class);
        }
    }

    @Test
    void anOversizedSheetIsSkippedWhileLookingForTheTimetable() throws IOException {
        byte[] file;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet huge = workbook.createSheet("Довідка");
            huge.createRow(0).createCell(0).setCellValue("x");
            huge.createRow(1_048_575).createCell(16_383).setCellValue("кут");
            workbook.createSheet("Порожній").createRow(0).createCell(0).setCellValue("нічого");
            file = bytes(workbook);
        }

        // завеликий аркуш не ламає пошук: файл без розкладу пояснюється як завжди
        assertThatThrownBy(() -> new TimetableWorkbookParser().parse(file, null))
                .isInstanceOf(ImportParseException.class)
                .hasMessageStartingWith("У файлі немає аркуша з розкладом груп.");
    }

    private static byte[] bytes(XSSFWorkbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }
}
