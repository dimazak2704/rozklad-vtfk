package ua.vtc.rozklad.importer.timetable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * Read-only view of a POI sheet that knows merged regions (0-based rows/columns). A cell covered by
 * a vertical merge returns the region's text; a cell covered from the left (horizontal merge)
 * returns empty text, because the value belongs to the region's first column.
 * <p>
 * The parsers scan the sheet cell by cell up to its last row and column, and merged regions are indexed per
 * cell, so a sheet's size is bounded before anything is read: a few kilobytes of XML can declare a cell at
 * {@code XFD1048576} or one merge over the whole sheet (about 1.7e10 cells), which would hold an import worker
 * for hours or exhaust the heap. Regions are indexed only inside the used area (the scans never look outside
 * it). The college's timetable is about 640 x 160 cells with 3 200 merges covering 6 700 cells.
 */
final class SheetGrid {

    /** Rows x columns of the used area. */
    static final long MAX_CELLS = 2_000_000;
    static final int MAX_MERGED_REGIONS = 50_000;
    /** Cells covered by merges inside the used area (overlapping merges count every time). */
    static final long MAX_MERGED_CELLS = 500_000;

    private final Sheet sheet;
    private final Map<Long, CellRangeAddress> regions = new HashMap<>();
    private final int lastColumn;

    /** @throws SheetTooLargeException the sheet is bigger than any timetable (see the class comment) */
    SheetGrid(Sheet sheet) {
        this.sheet = sheet;
        int lastRow = sheet.getLastRowNum();
        int maxColumn = 0;
        for (Row row : sheet) {
            maxColumn = Math.max(maxColumn, row.getLastCellNum());
        }
        this.lastColumn = maxColumn - 1;
        if ((long) (lastRow + 1) * (lastColumn + 1) > MAX_CELLS
                || sheet.getNumMergedRegions() > MAX_MERGED_REGIONS) {
            throw new SheetTooLargeException(sheet.getSheetName());
        }
        long merged = 0;
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            int bottom = Math.min(region.getLastRow(), lastRow + 1);
            int right = Math.min(region.getLastColumn(), lastColumn + 1);
            for (int row = Math.max(0, region.getFirstRow()); row <= bottom; row++) {
                for (int column = Math.max(0, region.getFirstColumn()); column <= right; column++) {
                    if (++merged > MAX_MERGED_CELLS) {
                        throw new SheetTooLargeException(sheet.getSheetName());
                    }
                    regions.put(key(row, column), region);
                }
            }
        }
    }

    String sheetName() {
        return sheet.getSheetName();
    }

    int lastRow() {
        return sheet.getLastRowNum();
    }

    int lastColumn() {
        return lastColumn;
    }

    CellRangeAddress region(int row, int column) {
        return regions.get(key(row, column));
    }

    /** Text owned by this column at this row (see class comment). */
    String text(int row, int column) {
        CellRangeAddress region = region(row, column);
        if (region == null) {
            return rawText(row, column);
        }
        if (region.getFirstColumn() != column) {
            return "";
        }
        return rawText(region.getFirstRow(), region.getFirstColumn());
    }

    /** The cell starts a row of its region (not covered from above). */
    boolean startsAt(int row, int column) {
        CellRangeAddress region = region(row, column);
        return region == null || region.getFirstRow() == row;
    }

    /** A merged region at (top, column) covers every row up to {@code bottom}. */
    boolean spansRows(int top, int bottom, int column) {
        CellRangeAddress region = region(top, column);
        return region != null && region.getFirstRow() <= top && region.getLastRow() >= bottom
                && region.getFirstColumn() == column;
    }

    /** Excel reference with the sheet name, e.g. «Розклад друк!C10». */
    String location(int row, int column) {
        return sheet.getSheetName() + "!" + new CellReference(row, column).formatAsString(false);
    }

    private String rawText(int rowIndex, int column) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(column);
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> TextNormalizer.clean(cell.getStringCellValue());
            case NUMERIC -> formatNumber(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static long key(int row, int column) {
        return ((long) row << 20) | column;
    }
}
