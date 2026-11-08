package ua.vtc.rozklad.importer.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.DisciplineItem;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.TeacherItem;
import ua.vtc.rozklad.importer.normalize.Resolution.Outcome;
import ua.vtc.rozklad.importer.report.ScoredRef;

/**
 * r3-ai-imports-01, r3-ui-real-24: full names of a curriculum or a program are suggested for the
 * catalog entries the timetable writes short (the live catalog of 08.11.2026: «КМ» named «КМ»,
 * «Ін.мова» named «Іноземна мова»), and a name with an undecodable character is still compared.
 */
class CurriculumNamesMatchingTest {

    private static final UUID KM = id(1);
    private static final UUID SP = id(2);
    private static final UUID EU = id(3);
    private static final UUID EUIT = id(4);
    private static final UUID KB = id(5);
    private static final UUID LANGUAGE = id(6);
    private static final UUID UKRAINIAN = id(7);
    private static final UUID NDEKS = id(8);
    private static final UUID KG = id(9);
    private static final UUID BADUN = id(20);

    private static final CatalogSnapshot CATALOG = new CatalogSnapshot(List.of(),
            List.of(new TeacherItem(BADUN, "Бадунь", "Бадунь", "Бадунь", List.of())),
            List.of(new DisciplineItem(KM, "КМ", "КМ", List.of()),
                    new DisciplineItem(SP, "СП", "СП", List.of()),
                    new DisciplineItem(EU, "ЕУ", "ЕУ", List.of()),
                    new DisciplineItem(EUIT, "ЕУІТ", "ЕУІТ", List.of()),
                    new DisciplineItem(KB, "КБ", "КБ", List.of()),
                    new DisciplineItem(KG, "КГ", "КГ", List.of()),
                    new DisciplineItem(LANGUAGE, "Ін.мова", "Іноземна мова", List.of()),
                    new DisciplineItem(UKRAINIAN, "Укр.мова", "Українська мова", List.of()),
                    new DisciplineItem(NDEKS, "НДЕКС", "Надійність, діагностика та експлуатація комп'ютерних систем і мереж",
                            List.of())),
            List.of(), List.of());

    private final NormalizationEngine engine = new NormalizationEngine();

    @Test
    void fullNamesAreSuggestedForTheirShortCatalogForms() {
        assertThat(suggested("Комп'ютерні мережі")).first().extracting(ScoredRef::id).isEqualTo(KM);
        assertThat(suggested("Системне програмування")).first().extracting(ScoredRef::id).isEqualTo(SP);
        assertThat(suggested("Економіка та управління ІТ-проєктами")).extracting(ScoredRef::id).contains(EU, EUIT);
        assertThat(suggested("Іноземна мова (за професійним спрям.)")).first().extracting(ScoredRef::id).isEqualTo(LANGUAGE);
        assertThat(suggested("Українська мова (за проф.спрямув.)")).first().extracting(ScoredRef::id).isEqualTo(UKRAINIAN);
        assertThat(suggested("Кібербезпека")).first().extracting(ScoredRef::id).isEqualTo(KB);
    }

    @Test
    void suggestionsNeverBindByThemselves() {
        Resolution resolution = resolve("Комп'ютерні мережі");
        assertThat(resolution.outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(NormalizationEngine.row(resolution).needsDecision()).isTrue();
    }

    @Test
    void conjunctionsTaAndIAreTheSameWord() {
        Resolution resolution = resolve("Надійність, діагностика та експлуатація комп'ютерних систем та мереж");
        assertThat(resolution.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(resolution.matched().id()).isEqualTo(NDEKS);
    }

    @Test
    void unrelatedNamesStayNew() {
        assertThat(resolve("Соціологія і чинники успішного працевлаштування").outcome()).isEqualTo(Outcome.NEW);
        assertThat(resolve("Вибірковий освітній компонент 1").outcome()).isEqualTo(Outcome.NEW);
    }

    /** The timetable writes abbreviations: «КМ.» is not a full name and gets no initials-based guesses. */
    @Test
    void timetableAbbreviationsAreNotGuessedFrom() {
        Resolution resolution = engine.normalize(List.of(new EntityCandidate(EntityType.DISCIPLINE, "Кібер.",
                List.of(), null, null)), CATALOG, List.of()).resolutions().getFirst();
        assertThat(resolution.outcome()).isEqualTo(Outcome.NEW);
    }

    @Test
    void undecodableCharactersAreLeftOutWhenComparing() {
        Resolution resolution = engine.normalize(List.of(EntityCandidate.of(EntityType.TEACHER, "Бадун��", "рядок 12")),
                CATALOG, List.of()).resolutions().getFirst();
        assertThat(resolution.outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(resolution.suggestions()).extracting(ScoredRef::id).containsExactly(BADUN);
        assertThat(resolution.entity().raw()).isEqualTo("Бадун��");
    }

    private List<ScoredRef> suggested(String name) {
        Resolution resolution = resolve(name);
        assertThat(resolution.outcome()).as(name).isEqualTo(Outcome.AMBIGUOUS);
        return resolution.suggestions();
    }

    private Resolution resolve(String name) {
        return engine.normalize(List.of(new EntityCandidate(EntityType.DISCIPLINE, name, List.of(), name, "рядок 1")),
                CATALOG, List.of()).resolutions().getFirst();
    }

    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(n));
    }
}
