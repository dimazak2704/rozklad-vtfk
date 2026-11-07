package ua.vtc.rozklad.importer.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.LocalDate;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.importer.job.ImportParseException;
import ua.vtc.rozklad.importer.order.OrderDraft.Row;
import ua.vtc.rozklad.importer.order.OrderDraft.Section;
import ua.vtc.rozklad.importer.report.Issues;

/** A DOCX built like the college order (DOMAIN §8, rows of order №35/№105 photos). */
class OrderDocxParserTest {

    private final OrderDocxParser parser = new OrderDocxParser();

    @Test
    void readsHeaderRowsOfAllTypesRoomChangesNotesAndSignature() throws IOException {
        OrderDraft draft = parser.parse(collegeOrder("Р О З П О Р Я Д Ж Е Н Н Я  №35"), new Issues());

        assertThat(draft.number()).isEqualTo(35);
        assertThat(draft.kind()).isEqualTo(OrderKind.ORDER);
        assertThat(draft.date()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(draft.weekdayLabel()).isEqualTo("ПОНЕДІЛОК");
        assertThat(draft.parityLabel()).isEqualTo("ЗНАМЕННИК");
        assertThat(draft.source()).isEqualTo(OrderDraft.Source.DOCX);
        assertThat(draft.rows()).extracting(Row::groupCode, Row::pairLabel, Row::rowType)
                .containsExactly(
                        tuple("1ТК1", "1-4", OrderRowType.BLOCK),
                        tuple("1ОП2", "4", OrderRowType.ADDITIONAL),
                        tuple("1ТК1", "1", OrderRowType.CANCELLATION),
                        tuple("4КІ1", "2", OrderRowType.REPLACEMENT),
                        tuple("1Ф", "4", OrderRowType.REPLACEMENT),
                        tuple("2ОД", "6", OrderRowType.FINISHED),
                        tuple("1ОБ", "4", OrderRowType.AS_SCHEDULED),
                        tuple("1РТ1", "1", OrderRowType.ROOM_CHANGE));
        Row block = draft.rows().get(0);
        assertThat(block.fromDiscipline()).isEqualTo("ЗУ");
        assertThat(block.fromTeacher()).isEqualTo("Ємельяненко");
        assertThat(block.roomText()).isEqualTo("204");
        Row additional = draft.rows().get(1);
        assertThat(additional.fromDiscipline()).isNull();
        assertThat(additional.toDiscipline()).isEqualTo("БЖ");
        assertThat(additional.toTeacher()).isEqualTo("Погосян");
        Row cancellation = draft.rows().get(2);
        assertThat(cancellation.fromTeacher()).isEqualTo("Крисак Л. (п.)");
        assertThat(cancellation.reasonText()).isEqualTo("Відп.");
        assertThat(cancellation.toDiscipline()).isNull();
        Row replacement = draft.rows().get(3);
        assertThat(replacement).extracting(Row::fromDiscipline, Row::fromTeacher, Row::reasonText, Row::toDiscipline,
                Row::toTeacher, Row::roomText).containsExactly("СТП", "Кренцін", "Зв.зам.", "НДКС", "Непийвода", "6");
        Row subgroups = draft.rows().get(4);
        assertThat(subgroups.fromTeacher()).isEqualTo("Харченко О. / Михальчук");
        assertThat(subgroups.toTeacher()).isEqualTo("Овчар Ін. / Овчар Ів.");
        assertThat(subgroups.roomText()).isEqualTo("304 / 309");
        Row roomChange = draft.rows().get(7);
        assertThat(roomChange.section()).isEqualTo(Section.ROOM_CHANGES);
        assertThat(roomChange.roomText()).isEqualTo("401");
        assertThat(draft.notes()).containsExactly("2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.",
                "Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.");
        assertThat(draft.signatoryPosition()).isEqualTo("Заступник директора з НР");
        assertThat(draft.signatoryName()).isEqualTo("Микола ПОЛІЩУК");
    }

    @Test
    void amendmentKeepsTheParentNumber() throws IOException {
        OrderDraft draft = parser.parse(collegeOrder("ДОПОВНЕННЯ до розпорядження №35"), new Issues());
        assertThat(draft.kind()).isEqualTo(OrderKind.AMENDMENT);
        assertThat(draft.parentNumber()).isEqualTo(35);
        assertThat(draft.number()).isNull();
    }

    @Test
    void documentWithoutOrderTableIsRejected() throws IOException {
        XWPFDocument document = new XWPFDocument();
        document.createParagraph().createRun().setText("Просто лист");
        assertThatThrownBy(() -> parser.parse(bytes(document), new Issues()))
                .isInstanceOf(ImportParseException.class)
                .hasMessageContaining("таблиці розпорядження");
    }

    private static byte[] collegeOrder(String title) throws IOException {
        XWPFDocument document = new XWPFDocument();
        paragraph(document, title);
        paragraph(document, "По коледжу про деякі зміни в розкладі занять");
        paragraph(document, "На 19 жовтня 2026 рік");
        paragraph(document, "ПОНЕДІЛОК - ЗНАМЕННИК");
        XWPFTable table = document.createTable(1, 8);
        XWPFTableRow header = table.getRow(0);
        fill(header, "Група", "Пара", "За розкладом", "", "Причина заміни", "По заміні", "", "Ауд");
        merge(header, 5, 2);
        merge(header, 2, 2);
        row(table, "1ТК1", "1-4", "ЗУ", "Ємельяненко", "", "", "", "204");
        row(table, "1ОП2", "4", "", "", "", "БЖ", "Погосян", "403");
        merge(row(table, "1ТК1", "1", "Ін. мова", "Крисак Л. (п.)", "Відп.", "Пари не буде", "", ""), 5, 2);
        row(table, "4КІ1", "2", "СТП", "Кренцін", "Зв.зам.", "НДКС", "Непийвода", "6");
        XWPFTableRow subgroups = row(table, "1Ф", "4", "Ін.мова", "Харченко О.", "Зв.зам.", "Інформ.", "Овчар Ін.", "304");
        subgroups.getCell(3).addParagraph().createRun().setText("Михальчук");
        subgroups.getCell(6).addParagraph().createRun().setText("Овчар Ів.");
        subgroups.getCell(7).addParagraph().createRun().setText("309");
        row(table, "2ОД", "6", "НПРФ", "Яцуба", "Вичит.", "", "", "");
        merge(row(table, "1ОБ", "4", "За розкладом", "", "", "", "", ""), 2, 2);
        merge(row(table, "ЗАМІНА АУДИТОРІЙ", "", "", "", "", "", "", ""), 0, 8);
        row(table, "1РТ1", "1", "", "", "", "", "", "401");
        row(table, "", "", "", "", "", "", "", "");
        paragraph(document, "2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.");
        paragraph(document, "Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.");
        paragraph(document, "Заступник директора з НР\t\t\tМикола ПОЛІЩУК");
        return bytes(document);
    }

    private static void paragraph(XWPFDocument document, String text) {
        document.createParagraph().createRun().setText(text);
    }

    private static XWPFTableRow row(XWPFTable table, String... cells) {
        XWPFTableRow row = table.createRow();
        fill(row, cells);
        return row;
    }

    private static void fill(XWPFTableRow row, String... cells) {
        while (row.getTableCells().size() < cells.length) {
            row.addNewTableCell();
        }
        for (int i = 0; i < cells.length; i++) {
            XWPFTableCell cell = row.getCell(i);
            cell.removeParagraph(0);
            cell.addParagraph().createRun().setText(cells[i]);
        }
    }

    /** Horizontal merge the way Word stores it: grid span on the first cell, the others removed. */
    private static XWPFTableRow merge(XWPFTableRow row, int first, int span) {
        XWPFTableCell cell = row.getCell(first);
        if (cell.getCTTc().getTcPr() == null) {
            cell.getCTTc().addNewTcPr();
        }
        cell.getCTTc().getTcPr().addNewGridSpan().setVal(BigInteger.valueOf(span));
        for (int i = 1; i < span; i++) {
            row.removeCell(first + 1);
        }
        return row;
    }

    private static byte[] bytes(XWPFDocument document) throws IOException {
        try (document; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.write(out);
            return out.toByteArray();
        }
    }
}
