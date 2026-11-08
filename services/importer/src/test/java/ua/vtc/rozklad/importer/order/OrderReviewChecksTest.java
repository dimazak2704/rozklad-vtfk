package ua.vtc.rozklad.importer.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.importer.job.ImportHandler.ParseOutcome;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.order.OrderDraft.Row;
import ua.vtc.rozklad.importer.order.OrderDraft.Section;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.Severity;

/** Checks of an order draft on parse and on every revision (round 6 findings). */
class OrderReviewChecksTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneId.of("Europe/Kyiv"));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OrderImportHandler handler = OrderTestAccess.handler(CLOCK);

    /** r4-ai-imports-51: a weekday label the date does not have blocks applying; the label can be fixed. */
    @Test
    void weekdayLabelMustBeTheWeekdayOfTheDate() {
        assertThat(errors(revise(edit(LocalDate.of(2026, 10, 15), "П'ЯТНИЦЯ", rows()))))
                .containsExactly("У розпорядженні надруковано «П'ЯТНИЦЯ», а 15.10.2026 — четвер. Перевірте дату або день тижня.");
        assertThat(errors(revise(edit(LocalDate.of(2026, 10, 16), "ПʼЯТНИЦЯ", rows())))).isEmpty();
        assertThat(errors(revise(edit(LocalDate.of(2026, 10, 16), "СУБОТА", rows()))))
                .singleElement().asString().contains("не день тижня");
        assertThat(errors(revise(edit(LocalDate.of(2026, 10, 16), null, rows())))).isEmpty();
    }

    /** r3-ui-real-24: U+FFFD anywhere blocks applying until the text is corrected. */
    @Test
    void undecodableCharactersBlock() {
        OrderDraft draft = new OrderDraft(35, OrderKind.ORDER, null, LocalDate.of(2026, 10, 19), "ПОНЕДІЛОК", "ЗНАМЕННИК",
                List.of(new Row(Section.CHANGES, "2М", "5", OrderRowType.REPLACEMENT, "ІМД", "Масловата", "Зв.зам.",
                        "Вс.іст.", "Бадун��", "409", "рядок 12")),
                List.of(), null, "Микола ПОЛ�ЩУК", OrderDraft.Source.AI);
        Issues issues = new Issues();

        OrderTestAccess.checkReadable(draft, issues);

        assertThat(issues.rows()).extracting(ReportRow::severity).containsOnly(Severity.ERROR);
        assertThat(issues.rows()).extracting(ReportRow::location).containsExactlyInAnyOrder("заголовок", "рядок 12");
        assertThat(issues.rows()).allMatch(ReportRow::blocking);
    }

    /** r5-ai-imports-73: a sent type that contradicts the corrected text follows the text, with a WARN. */
    @Test
    void reviewedRowTypeFollowsTheText() {
        List<OrderDraftEdit.Row> rows = List.of(
                new OrderDraftEdit.Row(null, "1ОП1", "1", OrderRowType.REPLACEMENT, "ФВ", "Остап'юк", "Вичит.", null, null,
                        null),
                new OrderDraftEdit.Row(null, "2ОБ", "4", OrderRowType.FINISHED, "ФВ", "Папулова", "Вичит.", "Матем.",
                        "Стахова", "307"),
                new OrderDraftEdit.Row(null, "2ОБ", "5", OrderRowType.ADDITIONAL, null, null, null, "Матем.", "Стахова",
                        "307"));

        ParseOutcome outcome = revise(edit(LocalDate.of(2026, 10, 16), null, rows));

        OrderDraft draft = (OrderDraft) outcome.draft();
        assertThat(draft.rows()).extracting(Row::rowType)
                .containsExactly(OrderRowType.FINISHED, OrderRowType.REPLACEMENT, OrderRowType.ADDITIONAL);
        assertThat(outcome.issues()).filteredOn(row -> row.severity() == Severity.WARN).extracting(ReportRow::message)
                .containsExactly(
                        "Рядок позначено як «заміна», але за текстом це «вичитано» — взято «вичитано».",
                        "Рядок позначено як «вичитано», але за текстом це «заміна» — взято «заміна».");
    }

    /** r3-ai-imports-06: an order is stored as text, so nothing is promised to be created. */
    @Test
    void orderCreatesNoCatalogEntries() {
        for (EntityType type : EntityType.values()) {
            assertThat(handler.creates(type)).isFalse();
        }
    }

    private ParseOutcome revise(OrderDraftEdit edit) {
        OrderDraft before = new OrderDraft(null, OrderKind.ORDER, null, null, null, null, List.of(), List.of(), null, null,
                OrderDraft.Source.AI);
        JsonNode current = JSON.valueToTree(before);
        return handler.revise(current, edit);
    }

    private static OrderDraftEdit edit(LocalDate date, String weekday, List<OrderDraftEdit.Row> rows) {
        return new OrderDraftEdit(1L, 30, OrderKind.ORDER, null, date, weekday, null, rows, List.of(), null, null);
    }

    private static List<OrderDraftEdit.Row> rows() {
        return List.of(new OrderDraftEdit.Row(null, "4КІ1", "2", null, "СТП", "Кренцін", "Зв.зам.", "НДКС", "Непийвода",
                "6"));
    }

    private static List<String> errors(ParseOutcome outcome) {
        return outcome.issues().stream().filter(row -> row.severity() == Severity.ERROR).map(ReportRow::message).toList();
    }
}
