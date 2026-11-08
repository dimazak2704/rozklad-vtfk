package ua.vtc.rozklad.importer.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.curriculum.CurriculumDraft;
import ua.vtc.rozklad.importer.curriculum.CurriculumImportHandler;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.MappingDecision;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.support.TestCatalogs;

/**
 * r3-ai-imports-01: a curriculum never creates a discipline on its own. Elective placeholders
 * («Вибірковий освітній компонент 1») and plan names that are not in the catalog block applying
 * until the admin maps them or confirms creating them.
 */
class CurriculumNewDisciplinesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ELECTIVE = "Вибірковий освітній компонент 1";
    private static final String SOCIOLOGY = "Соціологія і чинники успішного працевлаштування";
    private static final JobOptions OPTIONS = new JobOptions(null, null, 7, List.of("4КІ2"), null);

    private final JobNormalizer normalizer = new JobNormalizer(handlers(), new NormalizationEngine());

    @Test
    void unknownPlanDisciplinesAndElectivePlaceholdersBlockApplying() {
        JobNormalizer.Normalized normalized = normalize(List.of());

        Map<String, ReportRow> rows = byRaw(normalized);
        assertThat(normalized.blocking()).isTrue();
        assertThat(rows.get(ELECTIVE).severity()).isEqualTo(Severity.ERROR);
        assertThat(rows.get(ELECTIVE).message()).contains("місце для вибіркової дисципліни")
                .doesNotContain("буде створено");
        assertThat(rows.get(SOCIOLOGY).severity()).isEqualTo(Severity.ERROR);
        assertThat(rows.get(SOCIOLOGY).message()).startsWith("Немає в каталозі.").contains("підтвердьте створення");
        // знайдене в каталозі не чіпаємо
        assertThat(rows.get("Сучасні технології проєктування").severity()).isEqualTo(Severity.OK);
        assertThat(normalized.rows()).noneMatch(row -> row.message() != null && row.message().contains("буде створено ("));
    }

    @Test
    void mappingOrConfirmingCreationUnblocks() {
        JobNormalizer.Normalized normalized = normalize(List.of(
                new MappingDecision(EntityType.DISCIPLINE, ELECTIVE, TestCatalogs.STP, null, false),
                new MappingDecision(EntityType.DISCIPLINE, SOCIOLOGY, null, JSON.createObjectNode(), false)));

        Map<String, ReportRow> rows = byRaw(normalized);
        assertThat(normalized.blocking()).isFalse();
        assertThat(rows.get(ELECTIVE).severity()).isEqualTo(Severity.OK);
        assertThat(rows.get(SOCIOLOGY).severity()).isEqualTo(Severity.INFO);
        assertThat(rows.get(SOCIOLOGY).message()).isEqualTo("Буде створено новий запис за рішенням адміністратора.");
    }

    @Test
    void electivePlaceholdersAreRecognized() {
        CurriculumImportHandler handler = curriculum();
        for (String name : List.of(ELECTIVE, "Вибіркова навчальна дисципліна 2", "ВК 3", "ВК1",
                "Дисципліна за вибором студента", "Дисципліни вільного вибору")) {
            assertThat(handler.createNeedsConfirmation(candidate(name))).as(name).get().asString()
                    .contains("місце для вибіркової дисципліни");
        }
        assertThat(handler.createNeedsConfirmation(candidate(SOCIOLOGY))).get().asString()
                .doesNotContain("вибіркової");
        assertThat(handler.createNeedsConfirmation(candidate("Вкладені системи"))).get().asString()
                .doesNotContain("вибіркової");
        assertThat(handler.createNeedsConfirmation(
                EntityCandidate.of(EntityType.GROUP, "4КІ2", null))).isEmpty();
    }

    private JobNormalizer.Normalized normalize(List<MappingDecision> mappings) {
        List<CurriculumDraft.Row> rows = new ArrayList<>();
        for (String name : List.of("Сучасні технології проєктування", SOCIOLOGY, ELECTIVE)) {
            rows.add(new CurriculumDraft.Row(name, name.equals(SOCIOLOGY) ? 2.0 : 4.0, 88, 150, 88, 48, 16, 24,
                    CurriculumDraft.ControlForm.CREDIT));
        }
        JsonNode draft = JSON.valueToTree(new CurriculumDraft(7, 22, rows, 10.0, CurriculumDraft.Source.AI_VISION));
        return normalizer.normalize(ImportKind.CURRICULUM, draft, OPTIONS, mappings, TestCatalogs.college());
    }

    private static Map<String, ReportRow> byRaw(JobNormalizer.Normalized normalized) {
        return normalized.rows().stream().filter(row -> row.type().isEntity())
                .collect(Collectors.toMap(ReportRow::raw, Function.identity()));
    }

    private static EntityCandidate candidate(String name) {
        return new EntityCandidate(EntityType.DISCIPLINE, name, List.of(), name, "рядок 1");
    }

    private static CurriculumImportHandler curriculum() {
        return new CurriculumImportHandler(null, null, JSON);
    }

    private static ImportHandlers handlers() {
        List<ImportHandler> all = new ArrayList<>();
        all.add(curriculum());
        for (ImportKind kind : ImportKind.values()) {
            if (kind != ImportKind.CURRICULUM) {
                ImportHandler other = mock(ImportHandler.class);
                when(other.kind()).thenReturn(kind);
                all.add(other);
            }
        }
        return new ImportHandlers(all);
    }
}
