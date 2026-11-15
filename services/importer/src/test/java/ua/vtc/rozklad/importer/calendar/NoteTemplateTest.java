package ua.vtc.rozklad.importer.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.importer.support.TestCalendars;

/** The test vectors of the shared contract §1.2 (the web form's «Скласти» renders the same). */
class NoteTemplateTest {

    @Test
    void contractVectors() {
        assertThat(render(TestCalendars.T_SUSPEND, "GROUPS", List.of("2КІ3"), "Електрорадіомонтажна практика",
                "2026-10-19", "2026-10-30", null, null))
                .isEqualTo("2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.");
        assertThat(render(TestCalendars.T_SUSPEND, "GROUPS", List.of("4КІ1"), "Основи національного спротиву",
                "2026-11-16", "2026-11-27", "401", null))
                .isEqualTo("4КІ1 – Основи національного спротиву з 16.11.26р. по 27.11.26 р., ауд. 401");
        assertThat(render(TestCalendars.T_DAYOFF, "ALL", List.of(), "Зимові канікули", "2026-12-28", "2026-12-30", null,
                null))
                .isEqualTo("Усі групи: зимові канікули з 28.12.26р. по 30.12.26 р.");
        assertThat(render("{хто} з {від}р. по {до} {режим}.", "GROUPS", List.of("4КІ1", "4КІ2", "4КІ3"), "", "2026-09-01",
                "2026-10-31", null, "ONLINE"))
                .isEqualTo("Групи 4КІ1, 4КІ2, 4КІ3 з 01.09.26р. по 31.10.26 навчаються – дистанційно.");
    }

    @Test
    void audienceOfCoursesAndEdgeCases() {
        assertThat(NoteTemplate.audience("COURSES", null, List.of(3))).isEqualTo("3 курс");
        assertThat(NoteTemplate.audience("COURSES", null, List.of(2, 1))).isEqualTo("1, 2 курси");
        assertThat(render("{хто}: {назва_мала}", "ALL", List.of(), "", "2026-12-28", "2026-12-30", null, null))
                .isEqualTo("Усі групи: занять немає");
        assertThat(render("{вид} {невідоме} [{ауд}] [x", "ALL", List.of(), "t", "2026-12-28", "2026-12-30", null, "MIXED"))
                .isEqualTo("Канікули {невідоме}  [x");
        assertThat(render("{режим}", "ALL", List.of(), "t", "2026-12-28", "2026-12-30", null, "OFFLINE"))
                .isEqualTo("навчаються очно");
        assertThat(NoteTemplate.render(null, null)).isNull();
    }

    private static String render(String template, String scope, List<String> groups, String title, String from,
                                 String to, String room, String mode) {
        return NoteTemplate.render(template, new NoteTemplate.Values(NoteTemplate.audience(scope, groups, null), title,
                "Канікули", LocalDate.parse(from), LocalDate.parse(to), room, mode));
    }
}
