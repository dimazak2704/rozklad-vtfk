package ua.vtc.rozklad.importer.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.normalize.Resolution.Outcome;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.support.TestCatalogs;

class NormalizationEngineTest {

    private final NormalizationEngine engine = new NormalizationEngine();

    @Test
    void teacherByDisplayNameAliasAndSurnameWithInitials() {
        assertThat(resolve(EntityType.TEACHER, "Кренцін")).satisfies(matched(TestCatalogs.KRENTSIN));
        assertThat(resolve(EntityType.TEACHER, "Непийвода М.")).satisfies(matched(TestCatalogs.NEPYIVODA));
        assertThat(resolve(EntityType.TEACHER, "Харченко О.")).satisfies(matched(TestCatalogs.KHARCHENKO_O));
        assertThat(resolve(EntityType.TEACHER, "Харченко Г.Ю.")).satisfies(matched(TestCatalogs.KHARCHENKO_G));
    }

    @Test
    void bareSurnameOfTwoTeachersNeedsADecision() {
        Resolution resolution = resolve(EntityType.TEACHER, "Харченко");
        assertThat(resolution.outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(resolution.suggestions()).extracting(ref -> ref.id())
                .containsExactlyInAnyOrder(TestCatalogs.KHARCHENKO_O, TestCatalogs.KHARCHENKO_G);
        ReportRow row = NormalizationEngine.row(resolution);
        assertThat(row.severity()).isEqualTo(Severity.WARN);
        assertThat(row.needsDecision()).isTrue();
    }

    @Test
    void typoInSurnameIsOnlyASuggestion() {
        Resolution resolution = resolve(EntityType.TEACHER, "Хорченко О.");
        assertThat(resolution.outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(resolution.suggestions()).singleElement()
                .satisfies(ref -> {
                    assertThat(ref.id()).isEqualTo(TestCatalogs.KHARCHENKO_O);
                    assertThat(ref.score()).isGreaterThanOrEqualTo(0.92).isLessThan(1.0);
                });
    }

    @Test
    void differentInitialsOrUnknownSurnameMeanNewTeacher() {
        assertThat(resolve(EntityType.TEACHER, "Харченко В.").outcome()).isEqualTo(Outcome.NEW);
        assertThat(resolve(EntityType.TEACHER, "Зоріна").outcome()).isEqualTo(Outcome.NEW);
        assertThat(NormalizationEngine.row(resolve(EntityType.TEACHER, "Зоріна")).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void disciplineByAbbreviationAliasAndKnownFullName() {
        assertThat(resolve(EntityType.DISCIPLINE, "СТП")).satisfies(matched(TestCatalogs.STP));
        assertThat(resolve(EntityType.DISCIPLINE, "Ін. мова.")).satisfies(matched(TestCatalogs.LANGUAGE));
        assertThat(resolve(EntityType.DISCIPLINE, "НДКС")).satisfies(matched(TestCatalogs.NDEKSM));
        // «НДЕКС» відсутнє в каталозі, але це відома скорочена назва НДЕКСМ (DOMAIN §10)
        assertThat(resolve(EntityType.DISCIPLINE, "НДЕКС")).satisfies(matched(TestCatalogs.NDEKSM));
        Resolution fullName = engine.normalize(List.of(new EntityCandidate(EntityType.DISCIPLINE, "Сучасні технології проєктування",
                List.of(), "Сучасні технології проєктування", null)), TestCatalogs.college(), List.of()).resolutions().getFirst();
        assertThat(fullName).satisfies(matched(TestCatalogs.STP));
    }

    @Test
    void groupsAndRoomsByCanonicalForm() {
        assertThat(resolve(EntityType.GROUP, "4КИ1")).satisfies(matched(TestCatalogs.GROUP_4KI1));
        assertThat(resolve(EntityType.GROUP, "4 КІ 2")).satisfies(matched(TestCatalogs.GROUP_4KI2));
        assertThat(resolve(EntityType.GROUP, "1КІ1").outcome()).isEqualTo(Outcome.NEW);
        assertThat(resolve(EntityType.ROOM, "С.З.")).satisfies(matched(TestCatalogs.GYM));
        assertThat(resolve(EntityType.ROOM, "ауд. 308")).satisfies(matched(TestCatalogs.ROOM_308));
    }

    @Test
    void adminMappingsOverrideMatching() {
        EntityCandidate kharchenko = EntityCandidate.of(EntityType.TEACHER, "Харченко", "A1");
        EntityCandidate zorina = EntityCandidate.of(EntityType.TEACHER, "Зоріна", "A2");
        EntityCandidate stale = EntityCandidate.of(EntityType.ROOM, "999", "A3");
        NormalizationResult result = engine.normalize(List.of(kharchenko, zorina, stale), TestCatalogs.college(), List.of(
                new MappingDecision(EntityType.TEACHER, "харченко", TestCatalogs.KHARCHENKO_G, null, true),
                new MappingDecision(EntityType.TEACHER, "Зоріна", null, JsonNodeFactory.instance.objectNode(), false),
                new MappingDecision(EntityType.ROOM, "999", UUID.randomUUID(), null, false)));

        assertThat(result.find(EntityType.TEACHER, "Харченко")).get().satisfies(resolution -> {
            assertThat(resolution.outcome()).isEqualTo(Outcome.MAPPED);
            assertThat(resolution.matched().id()).isEqualTo(TestCatalogs.KHARCHENKO_G);
        });
        assertThat(result.find(EntityType.TEACHER, "Зоріна")).get()
                .extracting(Resolution::outcome).isEqualTo(Outcome.NEW);
        assertThat(result.rows()).extracting(ReportRow::severity)
                .containsExactly(Severity.OK, Severity.INFO, Severity.ERROR);
        assertThat(result.rows().get(0).message()).contains("псевдонім");
        assertThat(result.rows().get(2).blocking()).isTrue();
    }

    private Resolution resolve(EntityType type, String raw) {
        return engine.normalize(List.of(EntityCandidate.of(type, raw, null)), TestCatalogs.college(), List.of())
                .resolutions().getFirst();
    }

    private static java.util.function.Consumer<Resolution> matched(UUID id) {
        return resolution -> {
            assertThat(resolution.outcome()).isEqualTo(Outcome.MATCHED);
            assertThat(resolution.matched().id()).isEqualTo(id);
        };
    }
}
