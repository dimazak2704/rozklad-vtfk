package ua.vtc.rozklad.importer.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.program.ProgramDraft.Hours;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;

/** r3-ai-imports-02, r4-ai-imports-53: what apply needs is checked while the job is in review. */
class ProgramReadinessTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Hours HOURS = new Hours(120, 40, 80, 20, null, 20, null, null, false, true);
    private static final NormalizationResult NOTHING = new NormalizationResult(List.of(), List.of());

    private final ProgramImportHandler handler = new ProgramImportHandler(null, null, null, null, JSON);

    @Test
    void missingGroupsAndWeeklyHoursAreBlockingParameterRows() {
        JsonNode draft = draft("Кібербезпека", HOURS, null);

        List<ReportRow> rows = handler.readiness(draft, JobOptions.none(), NOTHING);

        assertThat(rows).extracting(ReportRow::type).containsOnly(RowType.OPTIONS);
        assertThat(rows).allMatch(ReportRow::blocking).extracting(ReportRow::message).containsExactly(
                "Вкажіть групи, яким додати навантаження (параметри імпорту).",
                ProgramImportHandler.HOURS_PER_WEEK_REQUIRED);
        JobOptions fixed = new JobOptions(null, null, null, List.of("4КІ1"), BigDecimal.valueOf(2));
        assertThat(handler.readiness(draft, fixed, NOTHING)).isEmpty();
        // тижневі години з програми теж годяться
        assertThat(handler.readiness(draft("Кібербезпека", HOURS, 2.0), new JobOptions(null, null, null, List.of("4КІ1"),
                null), NOTHING)).isEmpty();
    }

    @Test
    void hoursWithoutClassroomHoursBlock() {
        Hours noClassroom = new Hours(120, null, 80, null, null, null, null, null, false, true);
        assertThat(handler.readiness(draft("Кібербезпека", noClassroom, 2.0),
                new JobOptions(null, null, null, List.of("4КІ1"), null), NOTHING))
                .extracting(ReportRow::message).containsExactly(ProgramImportHandler.CLASSROOM_REQUIRED);
    }

    @Test
    void aDisciplineIsNeverCreatedUnderTheFileName() {
        assertThat(handler.cannotCreate(new EntityCandidate(EntityType.DISCIPLINE, "program-x", List.of(), null, null)))
                .get().asString().contains("узято з назви файлу");
        assertThat(handler.cannotCreate(new EntityCandidate(EntityType.DISCIPLINE, "Кібербезпека", List.of(),
                "Кібербезпека", null))).isEmpty();
        assertThat(handler.cannotCreate(EntityCandidate.of(EntityType.GROUP, "4КІ9", null))).isPresent();
    }

    private static JsonNode draft(String name, Hours hours, Double hoursPerWeek) {
        return JSON.valueToTree(new ProgramDraft(name, name == null ? "program-x" : name, null, hours, null, hoursPerWeek,
                null, ProgramDraft.Source.AI, "текст"));
    }
}
