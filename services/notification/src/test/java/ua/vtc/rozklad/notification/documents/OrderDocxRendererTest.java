package ua.vtc.rozklad.notification.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.notification.support.Order35;

/** DOCX of order №35 in the college layout (DOMAIN §8, seed/orders/order-035-2026-10-19.jpg). */
class OrderDocxRendererTest {

    private final OrderDocxRenderer renderer = new OrderDocxRenderer();

    @Test
    void order35HasTheCollegeHeaderOnA4Portrait() throws IOException {
        try (XWPFDocument document = render(Order35.payload())) {
            CTPageSz size = document.getDocument().getBody().getSectPr().getPgSz();
            assertThat(new BigInteger(size.getW().toString())).isEqualTo(BigInteger.valueOf(11906));
            assertThat(new BigInteger(size.getH().toString())).isEqualTo(BigInteger.valueOf(16838));

            List<XWPFParagraph> header = document.getParagraphs().subList(0, 4);
            assertThat(header).extracting(XWPFParagraph::getText).containsExactly(
                    "РОЗПОРЯДЖЕННЯ №35",
                    "По коледжу про деякі зміни в розкладі занять",
                    "На 19 жовтня 2026 р.",
                    "ПОНЕДІЛОК - ЗНАМЕННИК");
            assertThat(header).allSatisfy(paragraph -> {
                assertThat(paragraph.getAlignment()).isEqualTo(ParagraphAlignment.CENTER);
                assertThat(paragraph.getRuns()).allSatisfy(run -> {
                    assertThat(run.isBold()).isTrue();
                    assertThat(run.getFontFamily()).isEqualTo("Liberation Serif");
                });
            });
            // «Р О З П О Р Я Д Ж Е Н Н Я» — розрідженими літерами, як у бланку коледжу
            assertThat(header.getFirst().getRuns().getFirst().getCharacterSpacing()).isPositive();
        }
    }

    @Test
    void tableHasMergedScheduleAndReplacementHeaders() throws IOException {
        try (XWPFDocument document = render(Order35.payload())) {
            XWPFTable table = document.getTables().getFirst();
            XWPFTableRow head = table.getRow(0);
            assertThat(cellTexts(head)).containsExactly("Група", "Пара", "За розкладом", "Причина заміни",
                    "По заміні", "Ауд");
            assertThat(span(head.getCell(2))).isEqualTo(2);
            assertThat(span(head.getCell(4))).isEqualTo(2);
            assertThat(head.isRepeatHeader()).isTrue();
            assertThat(table.getCTTbl().getTblGrid().sizeOfGridColArray()).isEqualTo(8);
        }
    }

    @Test
    void everyRowTypeOfOrder35IsLaidOutLikeTheCollegeForm() throws IOException {
        try (XWPFDocument document = render(Order35.payload())) {
            XWPFTable table = document.getTables().getFirst();
            // 1 заголовок + 16 рядків змін + порожній + «ЗАМІНА АУДИТОРІЙ» + порожній
            assertThat(table.getNumberOfRows()).isEqualTo(1 + 16 + 3);

            XWPFTableRow block = table.getRow(1);
            assertThat(cellTexts(block)).containsExactly("1ТК1", "1-4", "ЗУ", "Ємельяненко", "", "", "", "204");

            XWPFTableRow additional = table.getRow(2);
            assertThat(cellTexts(additional)).containsExactly("1ОП2", "4", "ДОДАТКОВО", "", "БЖ", "Погосян", "403");
            assertThat(span(additional.getCell(2))).isEqualTo(2);

            XWPFTableRow cancellation = table.getRow(3);
            assertThat(cellTexts(cancellation))
                    .containsExactly("1ТК1", "1", "Ін. мова", "Крисак Л. (п.)", "Відп.", "Пари не буде", "");
            assertThat(span(cancellation.getCell(5))).isEqualTo(2);

            XWPFTableRow replacement = table.getRow(15);
            assertThat(cellTexts(replacement))
                    .containsExactly("4КІ1", "2", "СТП", "Кренцін", "Зв.зам.", "НДКС", "Непийвода", "6");

            XWPFTableRow section = table.getRow(18);
            assertThat(cellTexts(section)).containsExactly("ЗАМІНА АУДИТОРІЙ");
            assertThat(span(section.getCell(0))).isEqualTo(8);
        }
    }

    @Test
    void notesAreBoldAndTheSignatureEndsTheDocument() throws IOException {
        try (XWPFDocument document = render(Order35.payload())) {
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            List<String> texts = paragraphs.stream().map(XWPFParagraph::getText).toList();
            assertThat(texts).containsSubsequence(Order35.NOTES);
            paragraphs.stream().filter(paragraph -> Order35.NOTES.contains(paragraph.getText()))
                    .flatMap(paragraph -> paragraph.getRuns().stream())
                    .forEach(run -> assertThat(run.isBold()).isTrue());
            XWPFParagraph signature = paragraphs.getLast();
            assertThat(signature.getText()).isEqualTo("Заступник директора з НР\tМикола ПОЛІЩУК");
            assertThat(signature.getRuns()).allMatch(XWPFRun::isBold);
        }
    }

    @Test
    void subgroupsArePrintedOneUnderAnotherAndRoomChangesGetTheirSection() throws IOException {
        OrderPayload order = withRows(
                List.of(Order35.row("1Ф", "4", OrderRowType.REPLACEMENT, "Ін.мова", "Харченко О./Михальчук",
                        "Зв.зам.", "Інформ.", "Овчар Ін./Овчар Ів.", "304/309", null)),
                List.of(Order35.row("1РТ1", "1", OrderRowType.ROOM_CHANGE, null, null, null, null, null, "401",
                        null)));
        try (XWPFDocument document = render(order)) {
            XWPFTable table = document.getTables().getFirst();
            XWPFTableCell teachers = table.getRow(1).getCell(3);
            assertThat(teachers.getParagraphs().getFirst().getRuns()).extracting(run -> run.getText(0))
                    .containsExactly("Харченко О.", "Михальчук");
            assertThat(teachers.getText()).isEqualTo("Харченко О.\nМихальчук");
            assertThat(table.getRow(1).getCell(7).getParagraphs().getFirst().getRuns()).extracting(run -> run.getText(0))
                    .containsExactly("304", "309");
            assertThat(cellTexts(table.getRow(3))).containsExactly("ЗАМІНА АУДИТОРІЙ");
            assertThat(cellTexts(table.getRow(4))).containsExactly("1РТ1", "1", "", "", "", "", "", "401");
            assertThat(table.getNumberOfRows()).isEqualTo(5);
        }
    }

    @Test
    void asScheduledAndFinishedRowsUseTheirWording() throws IOException {
        OrderPayload order = withRows(List.of(
                Order35.row("1ОБ", "4", OrderRowType.AS_SCHEDULED, null, null, null, null, null, null, null),
                Order35.row("2КІ1", "6", OrderRowType.FINISHED, "Іст.Укр.", "Романюк", null, null, null, null, null)),
                List.of());
        try (XWPFDocument document = render(order)) {
            XWPFTable table = document.getTables().getFirst();
            assertThat(cellTexts(table.getRow(1))).containsExactly("1ОБ", "4", "За розкладом", "", "", "", "");
            assertThat(cellTexts(table.getRow(2)))
                    .containsExactly("2КІ1", "6", "Іст.Укр.", "Романюк", "Вичит.", "", "", "");
        }
    }

    @Test
    void mergedSubgroupsSpanTheReplacementColumns() throws IOException {
        OrderPayload order = withRows(List.of(Order35.row("1КІ2", "2", OrderRowType.MERGE_SUBGROUPS, "Ін.мова",
                "Крисак Л. (п.)", "Відп.", "Ін.мова", "Можаровська", "12", null)), List.of());
        try (XWPFDocument document = render(order)) {
            XWPFTable table = document.getTables().getFirst();
            assertThat(cellTexts(table.getRow(1))).containsExactly("1КІ2", "2", "Ін.мова", "Крисак Л. (п.)", "Відп.",
                    "Об'єднання підгруп: Ін.мова (Можаровська) — обидві підгрупи", "12");
        }
    }

    @Test
    void amendmentNamesItsMainOrderAndDraftHasNoNumber() throws IOException {
        OrderPayload amendment = new OrderPayload(UUID.randomUUID(), 36, Order35.DATE, OrderKind.AMENDMENT,
                Order35.ORDER_ID, OrderStatus.PUBLISHED, null, null, "ЗНАМЕННИК", List.of(), List.of(), List.of(),
                null, null, null, List.of(), List.of());
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(renderer.render(amendment, 35)))) {
            assertThat(document.getParagraphs()).extracting(XWPFParagraph::getText).startsWith(
                    "ДОПОВНЕННЯ №36", "до розпорядження №35", "По коледжу про деякі зміни в розкладі занять",
                    "На 19 жовтня 2026 р.", "ПОНЕДІЛОК - ЗНАМЕННИК");
        }
        try (XWPFDocument draft = render(Order35.payload(OrderStatus.DRAFT, null))) {
            assertThat(draft.getParagraphs().getFirst().getText()).isEqualTo("РОЗПОРЯДЖЕННЯ № ____");
        }
    }

    @Test
    void writesOrder35ForVisualComparison() throws IOException {
        Path output = Path.of("target", "test-output", "order-035-2026-10-19.docx");
        Files.createDirectories(output.getParent());
        Files.write(output, renderer.render(Order35.payload(), null));
        assertThat(output).isNotEmptyFile();
    }

    private XWPFDocument render(OrderPayload order) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(renderer.render(order, null)));
    }

    private static OrderPayload withRows(List<OrderPayload.Item> items, List<OrderPayload.Item> roomChanges) {
        OrderPayload base = Order35.payload();
        return new OrderPayload(base.id(), base.number(), base.date(), base.kind(), base.parentId(), base.status(),
                base.title(), base.weekdayLabel(), base.parityLabel(), items, roomChanges, List.of(),
                base.signatoryPosition(), base.signatoryName(), base.semesterName(), base.affectedGroups(),
                base.affectedTeachers());
    }

    private static List<String> cellTexts(XWPFTableRow row) {
        return row.getTableCells().stream().map(cell -> cell.getText().replace("\n", "/")).toList();
    }

    private static int span(XWPFTableCell cell) {
        var properties = cell.getCTTc().getTcPr();
        return properties != null && properties.isSetGridSpan() ? properties.getGridSpan().getVal().intValue() : 1;
    }
}
