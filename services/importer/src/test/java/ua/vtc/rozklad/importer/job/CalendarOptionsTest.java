package ua.vtc.rozklad.importer.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.web.InvalidRequestException;

/** Options of ACADEMIC_CALENDAR_XLSX and the catalog fields it reads. */
class CalendarOptionsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void calendarOptionsAreFoldedAndRoundTrip() {
        JobOptions options = JobOptions.from(JSON.readTree("""
                {"sheet":"2026-2027","academicYear":"2026/2027","skipCodes":["p", "Р"],"skipGroups":["4КІ-1"],
                 "titles":{"HC":"ОНС"},"rooms":{}}"""));

        assertThat(options.academicYear()).isEqualTo("2026/2027");
        assertThat(options.skipCodes()).containsExactly("Р");
        assertThat(options.skipGroups()).containsExactly("4КІ1");
        assertThat(options.effectiveTitles()).isEqualTo(Map.of("НС", "ОНС"));
        assertThat(options.effectiveRooms()).isEmpty();
        assertThat(JobOptions.from(options.toJson())).isEqualTo(options);
    }

    @Test
    void ownersDefaultsApplyWhenTitlesAndRoomsAreAbsent() {
        JobOptions options = JobOptions.none();
        assertThat(options.effectiveTitles()).isEqualTo(Map.of("НС", "Основи національного спротиву"));
        assertThat(options.effectiveRooms()).isEqualTo(Map.of("НС", "401"));
        assertThat(options.toJson().has("titles")).isFalse();
    }

    @Test
    void invalidCalendarOptionsAreFieldErrors() {
        assertThatThrownBy(() -> JobOptions.from(JSON.readTree("""
                {"academicYear":"2026/2028","skipCodes":"Р","skipGroups":["не група"],"titles":{"НС":""},"rooms":[1]}""")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.errors())
                        .extracting(Map.Entry::getKey)
                        .containsExactlyInAnyOrder("options.academicYear", "options.skipCodes", "options.skipGroups",
                                "options.titles", "options.rooms"));
    }

    @Test
    void periodCodeFoldsLatinLookalikes() {
        assertThat(JobOptions.periodCode(" hп ")).isEqualTo("НП");
        assertThat(JobOptions.periodCode("K")).isEqualTo("К");
        assertThat(JobOptions.periodCode("зу")).isEqualTo("ЗУ");
    }

    @Test
    void snapshotReadsPeriodKindsAndActiveFlagsAndOlderSnapshotsToo() {
        CatalogSnapshot snapshot = JSON.treeToValue(JSON.readTree("""
                {"groups":[{"id":"00000000-0000-4000-8000-000000000301","code":"4КІ1","course":4,"subgroups":[],
                            "active":false}],
                 "periodKinds":[{"id":"00000000-0000-4000-8000-000000000901","code":"ЗУ","name":"Збори",
                                 "effect":"SUSPEND","color":null,"aliases":["З"],"noteTemplate":"{хто}","active":true,
                                 "builtin":true}]}"""), CatalogSnapshot.class);
        assertThat(snapshot.groups().getFirst().isActive()).isFalse();
        assertThat(snapshot.periodKinds()).singleElement().satisfies(kind -> {
            assertThat(kind.aliases()).containsExactly("З");
            assertThat(kind.isActive()).isTrue();
        });
        JsonNode older = JSON.readTree("{\"groups\":[{\"id\":\"00000000-0000-4000-8000-000000000301\",\"code\":\"4КІ1\"}]}");
        CatalogSnapshot old = JSON.treeToValue(older, CatalogSnapshot.class);
        assertThat(old.groups().getFirst().isActive()).isTrue();
        assertThat(old.periodKinds()).isEmpty();
        assertThat(old.groups().getFirst().active()).isNull();
    }
}
