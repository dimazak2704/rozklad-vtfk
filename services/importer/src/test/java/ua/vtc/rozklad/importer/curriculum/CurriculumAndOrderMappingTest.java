package ua.vtc.rozklad.importer.curriculum;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.importer.ai.AiExtractions.CurriculumExtraction;
import ua.vtc.rozklad.importer.ai.AiExtractions.CurriculumRow;
import ua.vtc.rozklad.importer.ai.AiExtractions.OrderExtraction;
import ua.vtc.rozklad.importer.ai.AiExtractions.OrderRow;
import ua.vtc.rozklad.importer.order.OrderDraft;
import ua.vtc.rozklad.importer.order.OrderTestAccess;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.Severity;

/** Validation of AI answers before they become drafts (curriculum sums, order enums and dates). */
class CurriculumAndOrderMappingTest {

    @Test
    void curriculumChecksWeeklyHoursSumsAndTotals() {
        CurriculumExtraction extraction = new CurriculumExtraction(7, 22, List.of(
                new CurriculumRow("Комп'ютерні мережі", 4.0, 88, 150, 88, 48, null, 40, "EXAM"),
                new CurriculumRow("Надійність, діагностика та експлуатація комп'ютерних систем та мереж", 2.0, 44, 90, 44, 24,
                        null, 20, "CREDIT"),
                new CurriculumRow("Економіка та управління ІТ-проєктами", 3.0, 60, 120, 66, 24, 20, 20, "EXAM"),
                new CurriculumRow("Факультатив", null, null, null, null, null, null, null, "NONE"),
                new CurriculumRow("Фізичне виховання", 2.0, 44, 210, 138, 4, 134, null, "погано видно")),
                9.0, List.of("Скан низької якості."));
        Issues issues = new Issues();

        CurriculumDraft draft = CurriculumImportHandler.toDraft(extraction, 7, CurriculumDraft.Source.AI_VISION, issues);

        assertThat(draft.rows()).extracting(CurriculumDraft.Row::discipline).hasSize(4).doesNotContain("Факультатив");
        assertThat(draft.rows().getLast().controlForm()).isEqualTo(CurriculumDraft.ControlForm.UNKNOWN);
        assertThat(issues.rows()).extracting(ReportRow::message)
                .anyMatch(message -> message.contains("3 год/тиж × 22 тиж ≠ 60"))
                .anyMatch(message -> message.contains("разом 64 год, а аудиторних — 66"))
                .anyMatch(message -> message.contains("Сума годин на тиждень за рядками 11, а в рядку «Всього» — 9"))
                .anyMatch(message -> message.contains("Факультатив") || message.contains("годин на тиждень — рядок пропущено"))
                .anyMatch(message -> message.equals("ШІ: Скан низької якості."));
        assertThat(issues.hasErrors()).isFalse();
    }

    @Test
    void emptyCurriculumIsAnError() {
        Issues issues = new Issues();
        CurriculumImportHandler.toDraft(new CurriculumExtraction(7, 22, List.of(), null, List.of()), 7,
                CurriculumDraft.Source.AI_VISION, issues);
        assertThat(issues.rows()).anyMatch(row -> row.severity() == Severity.ERROR);
    }

    @Test
    void orderAnswerIsValidatedAndCanonicalized() {
        OrderExtraction extraction = new OrderExtraction(35, "ORDER", null, "19.10.2026", "ПОНЕДІЛОК", "ЗНАМЕННИК", List.of(
                new OrderRow("CHANGES", "4 КИ 1", "2", "REPLACEMENT", "СТП", "Кренцін", "Зв.зам.", "НДКС", "Непийвода", "6"),
                new OrderRow("CHANGES", "1ТК1", "1", "WHATEVER", "Ін. мова", "Крисак Л. (п.)", "Відп.", "Пари не буде", null, null),
                new OrderRow("ROOM_CHANGES", "1РТ1", "1", "REPLACEMENT", null, null, null, null, null, "401"),
                new OrderRow("CHANGES", "Спортзал", "3", "BLOCK", null, null, null, null, null, null)),
                List.of(" Примітка "), "Заступник директора з НР", "Микола ПОЛІЩУК", List.of());
        Issues issues = new Issues();

        OrderDraft draft = OrderTestAccess.fromAi(extraction, issues);

        assertThat(draft.date()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(draft.rows()).extracting(OrderDraft.Row::groupCode).containsExactly("4КІ1", "1ТК1", "1РТ1", "Спортзал");
        assertThat(draft.rows()).extracting(OrderDraft.Row::rowType).containsExactly(OrderRowType.REPLACEMENT,
                OrderRowType.CANCELLATION, OrderRowType.ROOM_CHANGE, OrderRowType.BLOCK);
        assertThat(draft.notes()).containsExactly("Примітка");
        assertThat(issues.rows()).extracting(ReportRow::message)
                .anyMatch(message -> message.contains("Невідомий тип рядка «WHATEVER»"))
                .anyMatch(message -> message.contains("«Спортзал» не схоже на код групи"));
    }
}
