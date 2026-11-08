package ua.vtc.rozklad.notification.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.TableRowHeightRule;
import org.apache.poi.xwpf.usermodel.TableWidthType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabStop;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabs;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblLayoutType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.contracts.events.OrderPayload.Item;
import ua.vtc.rozklad.notification.text.UaDates;

/**
 * Order (розпорядження) as DOCX in the college layout (DOMAIN §8, photos in {@code seed/orders}):
 * A4 portrait, Liberation Serif (metric twin of Times New Roman), bold centred header lines, the
 * changes table with merged «За розкладом» / «По заміні» headers, merged «Пари не буде» /
 * «ДОДАТКОВО» / «За розкладом» cells, the «ЗАМІНА АУДИТОРІЙ» section, bold notes and the
 * signature line. Rows are printed in the order core gave them (core sorts them).
 */
@Component
public class OrderDocxRenderer {

    static final String FONT = "Liberation Serif";
    /** Line under the heading (DOMAIN §8); core's {@code title} is the heading itself («РОЗПОРЯДЖЕННЯ №35»). */
    static final String SUBTITLE = "По коледжу про деякі зміни в розкладі занять";
    static final String ROOM_CHANGES_TITLE = "ЗАМІНА АУДИТОРІЙ";
    static final String NO_PAIR = "Пари не буде";
    static final String ADDITIONAL = "ДОДАТКОВО";
    static final String AS_SCHEDULED = "За розкладом";
    static final String FINISHED_REASON = "Вичит.";
    static final String MERGED = "Об'єднання підгруп";

    private static final Locale UK = Locale.forLanguageTag("uk");
    private static final int PAGE_WIDTH = 11906;
    private static final int PAGE_HEIGHT = 16838;
    private static final int MARGIN_LEFT = 1134;
    private static final int MARGIN_RIGHT = 567;
    private static final int MARGIN_TOP = 851;
    private static final int MARGIN_BOTTOM = 851;
    /** Група · Пара · дисципліна, викладач (за розкладом) · Причина · дисципліна, викладач (по заміні) · Ауд. */
    private static final int[] COLUMNS = {1100, 650, 1400, 1800, 1205, 1400, 1800, 850};
    private static final int TABLE_WIDTH = IntStream.of(COLUMNS).sum();
    private static final int TABLE_FONT = 11;
    private static final int TEXT_FONT = 12;
    private static final int TITLE_FONT = 14;
    private static final int NOTES_INDENT = 567;
    private static final int SIGNATURE_TAB = 6600;
    private static final int ROW_MIN_HEIGHT = 255;

    /**
     * @param parentNumber number of the main order for an amendment (printed as «до розпорядження №N»);
     *                     {@code null} when unknown or for a main order
     */
    public byte[] render(OrderPayload order, Integer parentNumber) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            setUpPage(document);
            header(document, order, parentNumber);
            table(document, order);
            notes(document, order.notes());
            signature(document, order);
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write order DOCX", e);
        }
    }

    private static void setUpPage(XWPFDocument document) {
        CTBody body = document.getDocument().getBody();
        CTSectPr section = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        size.setW(BigInteger.valueOf(PAGE_WIDTH));
        size.setH(BigInteger.valueOf(PAGE_HEIGHT));
        size.setOrient(STPageOrientation.PORTRAIT);
        CTPageMar margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margins.setLeft(BigInteger.valueOf(MARGIN_LEFT));
        margins.setRight(BigInteger.valueOf(MARGIN_RIGHT));
        margins.setTop(BigInteger.valueOf(MARGIN_TOP));
        margins.setBottom(BigInteger.valueOf(MARGIN_BOTTOM));
        margins.setHeader(BigInteger.valueOf(425));
        margins.setFooter(BigInteger.valueOf(425));
        margins.setGutter(BigInteger.ZERO);

        CTFonts fonts = CTFonts.Factory.newInstance();
        fonts.setAscii(FONT);
        fonts.setHAnsi(FONT);
        fonts.setCs(FONT);
        fonts.setEastAsia(FONT);
        document.createStyles().setDefaultFonts(fonts);
    }

    private static void header(XWPFDocument document, OrderPayload order, Integer parentNumber) {
        XWPFParagraph first = centered(document);
        String number = order.number() == null ? "№ ____" : "№" + order.number();
        if (order.kind() == OrderKind.AMENDMENT) {
            letterSpaced(first, "ДОПОВНЕННЯ");
            run(first, " " + number, TITLE_FONT, true);
            if (parentNumber != null) {
                run(centered(document), "до розпорядження №" + parentNumber, TEXT_FONT, true);
            }
        } else {
            letterSpaced(first, "РОЗПОРЯДЖЕННЯ");
            run(first, " " + number, TITLE_FONT, true);
        }
        run(centered(document), SUBTITLE, TEXT_FONT, true);
        run(centered(document), "На " + UaDates.dayMonthYear(order.date()), TEXT_FONT, true);
        run(centered(document), weekdayLine(order), 13, true);
        XWPFParagraph spacer = document.createParagraph();
        compact(spacer);
        spacer.setSpacingAfter(120);
    }

    static String weekdayLine(OrderPayload order) {
        String weekday = order.weekdayLabel() == null || order.weekdayLabel().isBlank()
                ? UaDates.weekday(order.date()).toUpperCase(UK)
                : order.weekdayLabel();
        return order.parityLabel() == null || order.parityLabel().isBlank()
                ? weekday
                : weekday + " - " + order.parityLabel();
    }

    private static void table(XWPFDocument document, OrderPayload order) {
        XWPFTable table = document.createTable();
        table.removeRow(0);
        setUpTable(table);

        XWPFTableRow head = newRow(table);
        head.setRepeatHeader(true);
        List<CellSpec> headCells = List.of(
                new CellSpec("Група", 0, 1, false, ParagraphAlignment.CENTER),
                new CellSpec("Пара", 1, 1, false, ParagraphAlignment.CENTER),
                new CellSpec("За розкладом", 2, 2, false, ParagraphAlignment.CENTER),
                new CellSpec("Причина заміни", 4, 1, false, ParagraphAlignment.CENTER),
                new CellSpec("По заміні", 5, 2, false, ParagraphAlignment.CENTER),
                new CellSpec("Ауд", 7, 1, false, ParagraphAlignment.CENTER));
        headCells.forEach(cell -> writeCell(head, cell, true));

        for (Item item : order.items()) {
            XWPFTableRow row = newRow(table);
            rowCells(item).forEach(cell -> writeCell(row, cell, false));
        }

        emptyRow(table);
        XWPFTableRow sectionRow = newRow(table);
        writeCell(sectionRow, new CellSpec(ROOM_CHANGES_TITLE, 0, COLUMNS.length, false, ParagraphAlignment.CENTER),
                false);
        if (order.roomChanges().isEmpty()) {
            emptyRow(table);
        }
        for (Item item : order.roomChanges()) {
            XWPFTableRow row = newRow(table);
            rowCells(item).forEach(cell -> writeCell(row, cell, false));
        }
    }

    /** Cells of one order row by its type (DOMAIN §8). */
    static List<CellSpec> rowCells(Item item) {
        List<CellSpec> cells = new ArrayList<>(8);
        cells.add(new CellSpec(item.groupCode(), 0, 1, false, ParagraphAlignment.LEFT));
        cells.add(new CellSpec(item.pairLabel(), 1, 1, false, ParagraphAlignment.CENTER));
        switch (item.rowType()) {
            case ADDITIONAL -> cells.add(new CellSpec(ADDITIONAL, 2, 2, false, ParagraphAlignment.LEFT));
            case AS_SCHEDULED -> cells.add(new CellSpec(AS_SCHEDULED, 2, 2, false, ParagraphAlignment.LEFT));
            default -> {
                cells.add(new CellSpec(item.fromDiscipline(), 2, 1, false, ParagraphAlignment.LEFT));
                cells.add(new CellSpec(item.fromTeacher(), 3, 1, true, ParagraphAlignment.LEFT));
            }
        }
        String reason = item.rowType() == OrderRowType.FINISHED && isBlank(item.reasonText())
                ? FINISHED_REASON
                : item.reasonText();
        cells.add(new CellSpec(reason, 4, 1, false, ParagraphAlignment.LEFT));
        if (item.rowType() == OrderRowType.CANCELLATION) {
            cells.add(new CellSpec(NO_PAIR, 5, 2, false, ParagraphAlignment.LEFT));
        } else if (item.rowType() == OrderRowType.MERGE_SUBGROUPS) {
            cells.add(new CellSpec(merged(item), 5, 2, false, ParagraphAlignment.LEFT));
        } else {
            cells.add(new CellSpec(item.toDiscipline(), 5, 1, false, ParagraphAlignment.LEFT));
            cells.add(new CellSpec(item.toTeacher(), 6, 1, true, ParagraphAlignment.LEFT));
        }
        cells.add(new CellSpec(item.roomText(), 7, 1, true, ParagraphAlignment.CENTER));
        return cells;
    }

    /** «Об'єднання підгруп: Ін.мова (Можаровська) — обидві підгрупи». */
    static String merged(Item item) {
        StringBuilder text = new StringBuilder(MERGED);
        boolean discipline = !isBlank(item.toDiscipline());
        boolean teacher = !isBlank(item.toTeacher());
        if (discipline || teacher) {
            text.append(':');
        }
        if (discipline) {
            text.append(' ').append(item.toDiscipline().strip());
        }
        if (teacher) {
            text.append(discipline ? " (" + item.toTeacher().strip() + ")" : " " + item.toTeacher().strip());
        }
        return text.append(" — обидві підгрупи").toString();
    }

    private static void setUpTable(XWPFTable table) {
        table.setWidthType(TableWidthType.DXA);
        table.setWidth(Integer.toString(TABLE_WIDTH));
        table.setTableAlignment(TableRowAlign.CENTER);
        table.setCellMargins(0, 70, 0, 70);
        table.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        CTTblPr properties = table.getCTTbl().getTblPr();
        CTTblLayoutType layout = properties.isSetTblLayout() ? properties.getTblLayout() : properties.addNewTblLayout();
        layout.setType(STTblLayoutType.FIXED);
        CTTblGrid grid = table.getCTTbl().getTblGrid() != null
                ? table.getCTTbl().getTblGrid()
                : table.getCTTbl().addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) {
            grid.removeGridCol(0);
        }
        for (int width : COLUMNS) {
            grid.addNewGridCol().setW(BigInteger.valueOf(width));
        }
    }

    private static XWPFTableRow newRow(XWPFTable table) {
        XWPFTableRow row = table.insertNewTableRow(table.getNumberOfRows());
        row.setHeight(ROW_MIN_HEIGHT);
        row.setHeightRule(TableRowHeightRule.AT_LEAST);
        row.setCantSplitRow(true);
        return row;
    }

    private static void emptyRow(XWPFTable table) {
        XWPFTableRow row = newRow(table);
        for (int column = 0; column < COLUMNS.length; column++) {
            writeCell(row, new CellSpec(null, column, 1, false, ParagraphAlignment.LEFT), false);
        }
    }

    private static void writeCell(XWPFTableRow row, CellSpec spec, boolean bold) {
        XWPFTableCell cell = row.addNewTableCell();
        CTTcPr properties = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTblWidth width = properties.isSetTcW() ? properties.getTcW() : properties.addNewTcW();
        width.setType(STTblWidth.DXA);
        width.setW(BigInteger.valueOf(IntStream.range(spec.fromColumn(), spec.fromColumn() + spec.span())
                .map(column -> COLUMNS[column]).sum()));
        if (spec.span() > 1) {
            (properties.isSetGridSpan() ? properties.getGridSpan() : properties.addNewGridSpan())
                    .setVal(BigInteger.valueOf(spec.span()));
        }
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        XWPFParagraph paragraph = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().getFirst();
        compact(paragraph);
        paragraph.setAlignment(spec.align());
        List<String> lines = lines(spec.text(), spec.slashSeparatesLines());
        for (int i = 0; i < lines.size(); i++) {
            XWPFRun run = run(paragraph, lines.get(i), TABLE_FONT, bold);
            if (i < lines.size() - 1) {
                run.addBreak();
            }
        }
    }

    /**
     * Cell text → printed lines. Two subgroups come as {@code Харченко О./Михальчук} or
     * {@code 304/309}; the college prints them one under another, so teacher and room cells break at
     * «/».
     */
    static List<String> lines(String text, boolean slashSeparatesLines) {
        if (isBlank(text)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.strip().split("\\R")) {
            if (slashSeparatesLines) {
                Arrays.stream(line.split("/")).map(String::strip).filter(part -> !part.isEmpty())
                        .forEach(lines::add);
            } else if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        return lines;
    }

    private static void notes(XWPFDocument document, List<String> notes) {
        boolean first = true;
        for (String note : notes) {
            if (isBlank(note)) {
                continue;
            }
            XWPFParagraph paragraph = document.createParagraph();
            compact(paragraph);
            paragraph.setIndentationLeft(NOTES_INDENT);
            if (first) {
                paragraph.setSpacingBefore(280);
                first = false;
            }
            run(paragraph, note.strip(), TEXT_FONT, true);
        }
    }

    private static void signature(XWPFDocument document, OrderPayload order) {
        boolean hasPosition = !isBlank(order.signatoryPosition());
        boolean hasName = !isBlank(order.signatoryName());
        if (!hasPosition && !hasName) {
            return;
        }
        XWPFParagraph paragraph = document.createParagraph();
        compact(paragraph);
        paragraph.setIndentationLeft(NOTES_INDENT);
        paragraph.setSpacingBefore(480);
        CTPPr properties = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        CTTabs tabs = properties.isSetTabs() ? properties.getTabs() : properties.addNewTabs();
        CTTabStop stop = tabs.addNewTab();
        stop.setVal(STTabJc.LEFT);
        stop.setPos(BigInteger.valueOf(SIGNATURE_TAB));
        if (hasPosition) {
            run(paragraph, order.signatoryPosition().strip(), TEXT_FONT, true);
        }
        if (hasName) {
            XWPFRun name = paragraph.createRun();
            name.addTab();
            style(name, TEXT_FONT, true);
            name.setText(order.signatoryName().strip());
        }
    }

    private static XWPFParagraph centered(XWPFDocument document) {
        XWPFParagraph paragraph = document.createParagraph();
        compact(paragraph);
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        return paragraph;
    }

    /** «Р О З П О Р Я Д Ж Е Н Н Я» as in the college form, but as real letter spacing (text stays searchable). */
    private static void letterSpaced(XWPFParagraph paragraph, String word) {
        XWPFRun run = run(paragraph, word, TITLE_FONT, true);
        run.setCharacterSpacing(80);
    }

    private static void compact(XWPFParagraph paragraph) {
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingBetween(1.0, LineSpacingRule.AUTO);
    }

    private static XWPFRun run(XWPFParagraph paragraph, String text, int size, boolean bold) {
        XWPFRun run = paragraph.createRun();
        style(run, size, bold);
        run.setText(text);
        return run;
    }

    private static void style(XWPFRun run, int size, boolean bold) {
        run.setFontFamily(FONT);
        run.setFontSize(size);
        run.setBold(bold);
        run.setLang("uk-UA");
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** One cell of a row: text, first grid column, merged column count. */
    record CellSpec(String text, int fromColumn, int span, boolean slashSeparatesLines, ParagraphAlignment align) {
    }
}
