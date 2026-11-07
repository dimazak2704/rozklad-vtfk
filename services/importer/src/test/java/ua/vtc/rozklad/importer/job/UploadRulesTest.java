package ua.vtc.rozklad.importer.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.support.SeedFiles;
import ua.vtc.rozklad.importer.web.InvalidRequestException;

/** File type sniffing on the real seed files and validation of upload options. */
class UploadRulesTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void sniffsRealFilesByContent() {
        assertThat(FileTypeSniffer.detect(SeedFiles.read("schedule-2025-26.xlsx"))).contains(FileType.XLSX);
        assertThat(FileTypeSniffer.detect(SeedFiles.read("programs/program-economics-it-projects.docx"))).contains(FileType.DOCX);
        assertThat(FileTypeSniffer.detect(SeedFiles.read("programs/working-program-cybersecurity.doc"))).contains(FileType.DOC);
        assertThat(FileTypeSniffer.detect(SeedFiles.read("orders/order-035-2026-10-19.jpg"))).contains(FileType.JPEG);
        assertThat(FileTypeSniffer.detect(SeedFiles.read("curriculum-ki-2023.pdf"))).contains(FileType.PDF);
        assertThat(FileTypeSniffer.detect("<html>не файл</html>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(FileTypeSniffer.detect(new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0, 0, 0})).isEmpty();
        assertThat(FileTypeSniffer.detect(null)).isEmpty();
    }

    @Test
    void optionsAreTypedAndCanonical() {
        JobOptions options = JobOptions.from(json.readTree("""
                {"sheet":"Розклад друк","versionName":"Осінь","semesterNumber":7,
                 "groupCodes":["4КИ1","4 КІ 2","4КІ1"],"hoursPerWeek":3}"""));
        assertThat(options.sheet()).isEqualTo("Розклад друк");
        assertThat(options.semesterNumber()).isEqualTo(7);
        assertThat(options.groupCodes()).containsExactly("4КІ1", "4КІ2");
        assertThat(options.hoursPerWeek()).isEqualByComparingTo(BigDecimal.valueOf(3));
        assertThat(JobOptions.from(options.toJson())).isEqualTo(options);
        assertThat(JobOptions.from(null)).isEqualTo(JobOptions.none());
    }

    @Test
    void invalidOptionsListEveryField() {
        assertThatThrownBy(() -> JobOptions.from(json.readTree("""
                {"semester":7,"semesterNumber":9,"groupCodes":["ФК"],"hoursPerWeek":-1}""")))
                .isInstanceOfSatisfying(InvalidRequestException.class, error -> assertThat(error.errors())
                        .extracting(java.util.Map.Entry::getKey)
                        .containsExactlyInAnyOrder("options.semester", "options.semesterNumber", "options.groupCodes",
                                "options.hoursPerWeek"));
        assertThatThrownBy(() -> JobOptions.from(json.readTree("[1]"))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void fileNamesLoseClientPaths() {
        assertThat(ImportJobService.safeFileName("C:\\Users\\Dell\\Розклад 2025.xlsx")).isEqualTo("Розклад 2025.xlsx");
        assertThat(ImportJobService.safeFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(ImportJobService.safeFileName(null)).isEqualTo("файл");
    }
}
