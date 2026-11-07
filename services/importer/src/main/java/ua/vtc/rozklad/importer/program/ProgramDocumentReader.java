package ua.vtc.rozklad.importer.program;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.importer.job.FileType;
import ua.vtc.rozklad.importer.job.ImportParseException;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/** Reads DOCX (POI XWPF), legacy DOC (POI HWPF) and PDF (PDFBox, text only) into a {@link ProgramDocument}. */
@Component
public class ProgramDocumentReader {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u0007]");

    public ProgramDocument read(FileType type, byte[] content) {
        try {
            return switch (type) {
                case DOCX -> readDocx(content);
                case DOC -> readDoc(content);
                case PDF -> readPdf(content);
                default -> throw new ImportParseException("Програму дисципліни можна завантажити як DOCX, DOC або PDF.");
            };
        } catch (IOException | RuntimeException e) {
            if (e instanceof ImportParseException parse) {
                throw parse;
            }
            throw new ImportParseException("Не вдалося прочитати документ (" + type.label() + "): він пошкоджений "
                    + "або захищений паролем.", e);
        }
    }

    private static ProgramDocument readDocx(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<String> paragraphs = new ArrayList<>();
            List<ProgramDocument.Table> tables = new ArrayList<>();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    addParagraph(paragraphs, paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    tables.add(new ProgramDocument.Table(tables.size(), docxRows(table)));
                }
            }
            return new ProgramDocument(paragraphs, tables);
        }
    }

    private static List<List<String>> docxRows(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = clean(cell.getText());
                int span = gridSpan(cell);
                // об'єднану по горизонталі клітинку повторюємо в кожній колонці сітки
                for (int i = 0; i < span; i++) {
                    cells.add(text);
                }
            }
            rows.add(cells);
        }
        return rows;
    }

    private static int gridSpan(XWPFTableCell cell) {
        CTTcPr properties = cell.getCTTc().getTcPr();
        if (properties == null || properties.getGridSpan() == null || properties.getGridSpan().getVal() == null) {
            return 1;
        }
        return Math.max(1, properties.getGridSpan().getVal().intValue());
    }

    private static ProgramDocument readDoc(byte[] content) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content))) {
            Range range = document.getRange();
            List<String> paragraphs = new ArrayList<>();
            List<ProgramDocument.Table> tables = new ArrayList<>();
            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph paragraph = range.getParagraph(i);
                if (paragraph.isInTable()) {
                    Table table = range.getTable(paragraph);
                    tables.add(new ProgramDocument.Table(tables.size(), docRows(table)));
                    i += table.numParagraphs() - 1;
                } else {
                    addParagraph(paragraphs, paragraph.text());
                }
            }
            return new ProgramDocument(paragraphs, tables);
        }
    }

    private static List<List<String>> docRows(Table table) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < table.numRows(); r++) {
            TableRow row = table.getRow(r);
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < row.numCells(); c++) {
                TableCell cell = row.getCell(c);
                cells.add(clean(cell.text()));
            }
            rows.add(cells);
        }
        return rows;
    }

    private static ProgramDocument readPdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            String text = new PDFTextStripper().getText(document);
            List<String> paragraphs = new ArrayList<>();
            for (String line : text.split("\\R")) {
                addParagraph(paragraphs, line);
            }
            return new ProgramDocument(paragraphs, List.of());
        } catch (InvalidPasswordException e) {
            throw new ImportParseException("PDF захищено паролем — збережіть його без пароля.", e);
        }
    }

    private static void addParagraph(List<String> paragraphs, String text) {
        String clean = clean(text);
        if (!clean.isEmpty()) {
            paragraphs.add(clean);
        }
    }

    private static String clean(String text) {
        return TextNormalizer.clean(CONTROL_CHARS.matcher(text == null ? "" : text).replaceAll(" "));
    }
}
