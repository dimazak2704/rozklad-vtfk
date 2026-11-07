package ua.vtc.rozklad.core.timetable.internal.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RFC 5545 details the calendar apps are strict about. */
class IcsCalendarWriterTest {

    private static final IcsCalendarWriter.Event EVENT = new IcsCalendarWriter.Event(
            "0190f0a0-0000-7000-8000-000000000001@rozklad.vtfk",
            Instant.parse("2026-10-19T05:30:00Z"), Instant.parse("2026-10-19T06:30:00Z"),
            Instant.parse("2026-10-01T09:15:00Z"),
            "СТП — Кренцін М.Д.", "ауд. 6", "Сучасні технології проєктування\nГрупа: 4КІ1");

    @Test
    void writesACalendarWithUtcTimesAndCrlfLines() {
        String calendar = IcsCalendarWriter.write("Розклад 4КІ1 — ВТФК", List.of(EVENT));
        String unfolded = calendar.replace("\r\n ", "");

        assertThat(calendar).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n").endsWith("END:VCALENDAR\r\n");
        assertThat(unfolded).contains("BEGIN:VEVENT\r\n", "UID:0190f0a0-0000-7000-8000-000000000001@rozklad.vtfk\r\n",
                "DTSTART:20261019T053000Z\r\n", "DTEND:20261019T063000Z\r\n", "DTSTAMP:20261001T091500Z\r\n",
                "SUMMARY:СТП — Кренцін М.Д.\r\n", "LOCATION:ауд. 6\r\n", "END:VEVENT\r\n",
                "DESCRIPTION:Сучасні технології проєктування\\nГрупа: 4КІ1\r\n");
        assertThat(calendar.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    void escapesTextValues() {
        assertThat(IcsCalendarWriter.escape("СТП, лаб.; C:\\temp\nдругий рядок"))
                .isEqualTo("СТП\\, лаб.\\; C:\\\\temp\\nдругий рядок");
    }

    @Test
    void foldsLongLinesAt75OctetsWithoutSplittingLetters() {
        String line = "DESCRIPTION:" + "Надійність, діагностика та експлуатація комп'ютерних систем і мереж ".repeat(3);

        String folded = IcsCalendarWriter.fold(line);

        assertThat(Arrays.stream(folded.split("\r\n")))
                .allSatisfy(physical -> assertThat(physical.getBytes(StandardCharsets.UTF_8).length)
                        .isLessThanOrEqualTo(75));
        assertThat(folded.replace("\r\n ", "")).isEqualTo(line);
        assertThat(folded.split("\r\n")).hasSizeGreaterThan(3);
    }

    @Test
    void shortLinesAreNotFolded() {
        assertThat(IcsCalendarWriter.fold("SUMMARY:СТП")).isEqualTo("SUMMARY:СТП");
    }
}
