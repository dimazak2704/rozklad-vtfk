package ua.vtc.rozklad.importer.order;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.importer.job.ImportParseException;
import ua.vtc.rozklad.importer.order.OrderDraft.Row;
import ua.vtc.rozklad.importer.order.OrderDraft.Section;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * ORDER_DOCUMENT from DOCX: header paragraphs (number, date, weekday and parity), the table
 * {@code Група | Пара | За розкладом (дисципліна | викладач) | Причина | По заміні (дисципліна |
 * викладач) | Ауд} with merged cells expanded by grid span, the «ЗАМІНА АУДИТОРІЙ» section, notes
 * and the signature line.
 */
@Component
public class OrderDocxParser {

    private static final Pattern SIGNATURE = Pattern.compile("^(.*?(?:директора|директор)[^\\t]*?)(?:\\t+|\\s{2,})\\s*(\\S.*)$");

    public OrderDraft parse(byte[] content, Issues issues) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<String> before = new ArrayList<>();
            List<String> after = new ArrayList<>();
            XWPFTable table = null;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFTable candidate && table == null && isOrderTable(candidate)) {
                    table = candidate;
                } else if (element instanceof XWPFParagraph paragraph) {
                    String text = TextNormalizer.clean(paragraph.getText());
                    if (!text.isEmpty()) {
                        (table == null ? before : after).add(paragraph.getText().strip());
                    }
                }
            }
            if (table == null) {
                throw new ImportParseException("У документі не знайдено таблиці розпорядження (стовпці «Група» і «Пара»).");
            }
            Header header = header(before, issues);
            List<Row> rows = rows(table, issues);
            Signature signature = signature(after);
            List<String> notes = after.stream()
                    .map(TextNormalizer::clean)
                    .filter(text -> signature == null || !text.equals(signature.line()))
                    .toList();
            return new OrderDraft(header.number(), header.kind(), header.parentNumber(), header.date(),
                    header.weekday(), header.parity(), rows, notes,
                    signature == null ? null : signature.position(), signature == null ? null : signature.name(),
                    OrderDraft.Source.DOCX);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ImportParseException parse) {
                throw parse;
            }
            throw new ImportParseException("Не вдалося прочитати розпорядження у форматі DOCX: файл пошкоджений.", e);
        }
    }

    private record Header(Integer number, OrderKind kind, Integer parentNumber, LocalDate date, String weekday,
                          String parity) {
    }

    private static Header header(List<String> lines, Issues issues) {
        Integer number = null;
        Integer parent = null;
        LocalDate date = null;
        String weekday = null;
        String parity = null;
        for (String line : lines) {
            Optional<Integer> amendment = OrderTexts.amendmentOf(line);
            if (amendment.isPresent() && parent == null) {
                parent = amendment.get();
            } else if (number == null) {
                number = OrderTexts.number(line).orElse(null);
            }
            if (date == null) {
                date = OrderTexts.dateInWords(line).orElse(null);
            }
            if (weekday == null) {
                Optional<List<String>> dayAndParity = OrderTexts.weekdayAndParity(line);
                if (dayAndParity.isPresent()) {
                    weekday = dayAndParity.get().get(0);
                    parity = dayAndParity.get().get(1);
                }
            }
        }
        if (date == null) {
            issues.warn(RowType.ORDER, null, "заголовок", "Не знайдено дати розпорядження («На 19 жовтня 2026 р.»).");
        }
        if (number == null && parent == null) {
            issues.info(RowType.ORDER, null, "заголовок", "Номер розпорядження не знайдено.");
        }
        OrderKind kind = parent != null ? OrderKind.AMENDMENT : OrderKind.ORDER;
        return new Header(number, kind, parent, date, weekday, parity);
    }

    private static boolean isOrderTable(XWPFTable table) {
        for (int i = 0; i < Math.min(3, table.getRows().size()); i++) {
            String text = TextNormalizer.key(String.join(" ", cells(table.getRows().get(i))));
            if (text.contains("група") && text.contains("пар")) {
                return true;
            }
        }
        return false;
    }

    /** Grid columns of the order layout, found from the header row. */
    private record Columns(int group, int pair, int fromDiscipline, int fromTeacher, int reason, int toDiscipline,
                           int toTeacher, int room) {
    }

    private static List<Row> rows(XWPFTable table, Issues issues) {
        List<XWPFTableRow> tableRows = table.getRows();
        int headerIndex = -1;
        Columns columns = null;
        for (int i = 0; i < Math.min(3, tableRows.size()) && columns == null; i++) {
            columns = columns(cells(tableRows.get(i)));
            headerIndex = i;
        }
        if (columns == null) {
            throw new ImportParseException("Не вдалося розпізнати заголовок таблиці розпорядження.");
        }
        List<Row> rows = new ArrayList<>();
        Section section = Section.CHANGES;
        for (int i = headerIndex + 1; i < tableRows.size(); i++) {
            List<String> cells = cells(tableRows.get(i));
            String rowText = TextNormalizer.key(String.join(" ", cells));
            if (rowText.replace(" ", "").contains("замінааудиторій")) {
                section = Section.ROOM_CHANGES;
                continue;
            }
            if (rowText.isBlank() || rowText.contains("група") && rowText.contains("пара")) {
                continue;
            }
            String location = "таблиця, рядок " + (i + 1);
            String group = cell(cells, columns.group());
            String pair = cell(cells, columns.pair());
            if (section == Section.ROOM_CHANGES) {
                rows.add(new Row(section, group, pair, OrderRowType.ROOM_CHANGE, null, null, null, null, null,
                        cell(cells, columns.room()), location));
                continue;
            }
            String fromDiscipline = cell(cells, columns.fromDiscipline());
            String fromTeacher = distinct(cell(cells, columns.fromTeacher()), fromDiscipline);
            String reason = cell(cells, columns.reason());
            String toDiscipline = cell(cells, columns.toDiscipline());
            String toTeacher = distinct(cell(cells, columns.toTeacher()), toDiscipline);
            OrderRowType type = OrderTexts.rowType(pair, fromDiscipline, fromTeacher, reason, toDiscipline, toTeacher);
            switch (type) {
                case CANCELLATION -> {
                    toDiscipline = null;
                    toTeacher = null;
                }
                case ADDITIONAL, AS_SCHEDULED -> {
                    fromDiscipline = null;
                    fromTeacher = null;
                }
                default -> {
                }
            }
            if (group == null) {
                issues.warn(RowType.ORDER, rowText, location, "У рядку розпорядження не вказано групи.");
            }
            rows.add(new Row(section, group, pair, type, fromDiscipline, fromTeacher, reason, toDiscipline, toTeacher,
                    cell(cells, columns.room()), location));
        }
        return rows;
    }

    private static Columns columns(List<String> header) {
        int group = -1;
        int pair = -1;
        int from = -1;
        int reason = -1;
        int to = -1;
        int room = -1;
        for (int i = 0; i < header.size(); i++) {
            String label = TextNormalizer.key(header.get(i));
            if (label.startsWith("груп") && group < 0) {
                group = i;
            } else if (label.startsWith("пар") && pair < 0) {
                pair = i;
            } else if (label.contains("за розкладом") && from < 0) {
                from = i;
            } else if (label.contains("причин") && reason < 0) {
                reason = i;
            } else if (label.contains("по заміні") && to < 0) {
                to = i;
            } else if (label.startsWith("ауд") && room < 0) {
                room = i;
            }
        }
        if (group < 0 || pair < 0 || from < 0 || to < 0) {
            return null;
        }
        // «За розкладом» і «По заміні» займають по дві колонки сітки: дисципліна | викладач
        return new Columns(group, pair, from, from + 1, reason, to, to + 1, room);
    }

    /** Cell texts expanded by grid span; lines inside a cell (two subgroups) are joined with « / ». */
    private static List<String> cells(XWPFTableRow row) {
        List<String> cells = new ArrayList<>();
        for (XWPFTableCell cell : row.getTableCells()) {
            List<String> lines = new ArrayList<>();
            cell.getParagraphs().forEach(paragraph -> {
                String text = TextNormalizer.clean(paragraph.getText());
                if (!text.isEmpty()) {
                    lines.add(text);
                }
            });
            String text = String.join(" / ", lines);
            CTTcPr properties = cell.getCTTc().getTcPr();
            int span = properties == null || properties.getGridSpan() == null || properties.getGridSpan().getVal() == null
                    ? 1 : Math.max(1, properties.getGridSpan().getVal().intValue());
            for (int i = 0; i < span; i++) {
                cells.add(text);
            }
        }
        return cells;
    }

    private static String cell(List<String> cells, int index) {
        if (index < 0 || index >= cells.size()) {
            return null;
        }
        String text = TextNormalizer.clean(cells.get(index));
        return text.isEmpty() ? null : text;
    }

    /** A cell merged over two grid columns repeats its text; keep it once. */
    private static String distinct(String value, String previous) {
        return value != null && value.equals(previous) ? null : value;
    }

    private record Signature(String line, String position, String name) {
    }

    private static Signature signature(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String raw = lines.get(i);
            Matcher matcher = SIGNATURE.matcher(raw);
            if (matcher.matches()) {
                return new Signature(TextNormalizer.clean(raw), TextNormalizer.clean(matcher.group(1)),
                        TextNormalizer.clean(matcher.group(2)));
            }
        }
        return null;
    }
}
