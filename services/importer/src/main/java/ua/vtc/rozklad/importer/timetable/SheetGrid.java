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
 */
final class SheetGrid {

    private final Sheet sheet;
    private final Map<Long, CellRangeAddress> regions = new HashMap<>();
    private final int lastColumn;

    SheetGrid(Sheet sheet) {
        this.sheet = sheet;
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            for (int row = region.getFirstRow(); row <= region.getLastRow(); row++) {
                for (int column = region.getFirstColumn(); column <= region.getLastColumn(); column++) {
                    regions.put(key(row, column), region);
                }
            }
        }
        int maxColumn = 0;
        for (Row row : sheet) {
            maxColumn = Math.max(maxColumn, row.getLastCellNum());
        }
        this.lastColumn = maxColumn - 1;
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
