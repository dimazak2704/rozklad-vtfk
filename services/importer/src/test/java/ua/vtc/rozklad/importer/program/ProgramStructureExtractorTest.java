package ua.vtc.rozklad.importer.program;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.importer.job.FileType;
import ua.vtc.rozklad.importer.job.ImportParseException;
import ua.vtc.rozklad.importer.program.ProgramDraft.Hours;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.support.SeedFiles;

/** Discipline programs from {@code seed/programs}: DOCX through XWPF, legacy DOC through HWPF. */
class ProgramStructureExtractorTest {

    private final ProgramDocumentReader reader = new ProgramDocumentReader();
    private final ProgramStructureExtractor extractor = new ProgramStructureExtractor();

    @Test
    void economicsDocxWithExamAndTextVersusTableMismatch() {
        Issues issues = new Issues();
        ProgramStructureExtractor.Extraction result = extract(FileType.DOCX, "programs/program-economics-it-projects.docx", issues);

        assertThat(result.disciplineName()).isEqualTo("Економіка та управління ІТ-проєктами");
        assertThat(result.source()).isEqualTo(ProgramDraft.Source.TABLE);
        Hours hours = result.hours();
        assertThat(hours.total()).isEqualTo(150);
        assertThat(hours.classroom()).isEqualTo(60);
        assertThat(hours.selfStudy()).isEqualTo(90);
        assertThat(hours.lectures()).isEqualTo(30);
        assertThat(hours.practice()).isNull();
        assertThat(hours.labs()).isEqualTo(20);
        assertThat(hours.seminars()).isEqualTo(10);
        assertThat(hours.exam()).isTrue();
        assertThat(hours.credit()).isFalse();
        assertThat(result.semester()).isEqualTo(7);
        assertThat(result.hoursPerWeek()).isEqualTo(3.0);
        assertThat(result.credits()).isEqualTo(5.0);
        assertThat(result.cycleCommission()).isEqualTo("економіки і фінансів");
        assertThat(issues.rows()).anyMatch(row -> row.severity() == Severity.WARN
                && row.message().contains("відведено 180 год") && row.message().contains("150"));
        assertThat(issues.rows()).anyMatch(row -> row.severity() == Severity.INFO && row.message().contains("семестр 7"));
    }

    @Test
    void cybersecurityLegacyDocWithCredit() {
        Issues issues = new Issues();
        ProgramStructureExtractor.Extraction result = extract(FileType.DOC, "programs/working-program-cybersecurity.doc", issues);

        Hours hours = result.hours();
        assertThat(hours).isNotNull();
        assertThat(hours.total()).isEqualTo(120);
        assertThat(hours.classroom()).isEqualTo(40);
        assertThat(hours.selfStudy()).isEqualTo(80);
        assertThat(hours.lectures()).isEqualTo(20);
        assertThat(hours.labs()).isEqualTo(20);
        assertThat(hours.credit()).isTrue();
        assertThat(hours.exam()).isFalse();
        assertThat(result.disciplineName()).isNotBlank();
    }

    @Test
    void ndeksmLegacyDocHasClassroomHoursOfTheProgram() {
        Issues issues = new Issues();
        ProgramStructureExtractor.Extraction result = extract(FileType.DOC, "programs/working-program-ndeksm.doc", issues);

        Hours hours = result.hours();
        assertThat(hours).isNotNull();
        // DOMAIN §4: навчальний план дає 24 + 20 = 44 год, а програма — 40
        assertThat(hours.classroom()).isEqualTo(40);
        assertThat(hours.total()).isEqualTo(hours.classroom() + hours.selfStudy());
    }

    @Test
    void fragmentWithoutSemesterAndWithCreditMark() {
        Issues issues = new Issues();
        ProgramStructureExtractor.Extraction result =
                extract(FileType.DOCX, "programs/program-business-communications-fragment.docx", issues);

        Hours hours = result.hours();
        assertThat(hours.total()).isEqualTo(90);
        assertThat(hours.classroom()).isEqualTo(60);
        assertThat(hours.selfStudy()).isEqualTo(30);
        assertThat(hours.lectures()).isEqualTo(20);
        assertThat(hours.practice()).isEqualTo(40);
        assertThat(hours.credit()).isTrue();
        assertThat(result.semester()).isNull();
        assertThat(result.disciplineName()).isNull();
        assertThat(issues.rows()).extracting(ReportRow::message).anyMatch(message -> message.contains("назви дисципліни"));
    }

    @Test
    void sentenceCaseKeepsAcronymsAndLowersConjunctions() {
        assertThat(ProgramStructureExtractor.sentenceCase("ЕКОНОМІКА ТА УПРАВЛІННЯ ІТ-ПРОЄКТАМИ"))
                .isEqualTo("Економіка та управління ІТ-проєктами");
        assertThat(ProgramStructureExtractor.sentenceCase("Кібербезпека")).isEqualTo("Кібербезпека");
    }

    @Test
    void unreadableDocumentIsAParseError() {
        assertThatThrownBy(() -> reader.read(FileType.DOCX, "не docx".getBytes()))
                .isInstanceOf(ImportParseException.class)
                .hasMessageContaining("Не вдалося прочитати документ");
    }

    /** r4-ai-imports-53: the name in the title line itself; description lines are never a name. */
    @Test
    void titleLineNameAndDescriptionLines() {
        assertThat(ProgramStructureExtractor.disciplineTitle(java.util.List.of(
                "Робоча програма навчальної дисципліни «Кібербезпека»", "Спеціальність 123 Комп'ютерна інженерія",
                "Загальний обсяг 120 годин")))
                .isEqualTo("Кібербезпека");
        assertThat(ProgramStructureExtractor.disciplineTitle(java.util.List.of(
                "РОБОЧА ПРОГРАМА НАВЧАЛЬНОЇ ДИСЦИПЛІНИ", "Спеціальність 123 Комп'ютерна інженерія")))
                .isNull();
        assertThat(ProgramStructureExtractor.disciplineTitle(java.util.List.of(
                "РОБОЧА ПРОГРАМА НАВЧАЛЬНОЇ ДИСЦИПЛІНИ", "«ЕКОНОМІКА ТА УПРАВЛІННЯ ІТ-ПРОЄКТАМИ»")))
                .isEqualTo("ЕКОНОМІКА ТА УПРАВЛІННЯ ІТ-ПРОЄКТАМИ");
    }

    private ProgramStructureExtractor.Extraction extract(FileType type, String file, Issues issues) {
        ProgramDocument document = reader.read(type, SeedFiles.read(file));
        return extractor.extract(document, issues);
    }
}
