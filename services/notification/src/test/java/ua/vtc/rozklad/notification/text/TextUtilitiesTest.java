package ua.vtc.rozklad.notification.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.notification.config.ServiceUrlsProperties;

class TextUtilitiesTest {

    @Test
    void htmlEscapesEverythingTelegramParses() {
        assertThat(Html.escape("<b>A & \"B\"</b>")).isEqualTo("&lt;b&gt;A &amp; &quot;B&quot;&lt;/b&gt;");
        assertThat(Html.escape(null)).isEmpty();
        assertThat(Html.link("https://x.ua/?a=1&b=\"2\"", "<текст>"))
                .isEqualTo("<a href=\"https://x.ua/?a=1&amp;b=&quot;2&quot;\">&lt;текст&gt;</a>");
    }

    @Test
    void shortTextIsNotSplit() {
        assertThat(Html.split("Привіт", 4000)).containsExactly("Привіт");
    }

    @Test
    void longTextIsSplitAtBlankLinesFirst() {
        String block = "x".repeat(30);
        String text = IntStream.range(0, 10).mapToObj(i -> block).collect(Collectors.joining("\n\n"));
        List<String> parts = Html.split(text, 100);
        assertThat(parts).allMatch(part -> part.length() <= 100);
        assertThat(String.join("\n\n", parts)).isEqualTo(text);
        assertThat(parts.getFirst()).isEqualTo(block + "\n\n" + block + "\n\n" + block);
    }

    @Test
    void hugeBlocksFallBackToLinesAndHugeLinesToSpaces() {
        String lines = IntStream.range(0, 6).mapToObj(i -> "line " + i + " " + "y".repeat(20))
                .collect(Collectors.joining("\n"));
        List<String> parts = Html.split(lines, 60);
        assertThat(parts).allMatch(part -> part.length() <= 60).hasSizeGreaterThan(1);
        assertThat(String.join("\n", parts)).isEqualTo(lines);

        String words = IntStream.range(0, 40).mapToObj(i -> "слово" + i).collect(Collectors.joining(" "));
        List<String> wordParts = Html.split(words, 50);
        assertThat(wordParts).allMatch(part -> part.length() <= 50);
        assertThat(String.join(" ", wordParts)).isEqualTo(words);

        assertThat(Html.split("z".repeat(25), 10)).containsExactly("z".repeat(10), "z".repeat(10), "z".repeat(5));
        assertThatThrownBy(() -> Html.split("abc", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ukrainianDates() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        assertThat(UaDates.dayMonth(monday)).isEqualTo("19 жовтня");
        assertThat(UaDates.dayMonthYear(monday)).isEqualTo("19 жовтня 2026 р.");
        assertThat(UaDates.weekdayDayMonth(monday)).isEqualTo("понеділок, 19 жовтня");
        assertThat(UaDates.weekday(LocalDate.of(2026, 10, 23))).isEqualTo("п’ятниця");
        assertThat(UaDates.weekdayShort(monday)).isEqualTo("Пн");
        assertThat(UaDates.range(monday, monday)).isEqualTo("понеділок, 19 жовтня");
        assertThat(UaDates.range(monday, LocalDate.of(2026, 10, 21))).isEqualTo("19–21 жовтня");
        assertThat(UaDates.range(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2)))
                .isEqualTo("30 жовтня – 2 листопада");
        assertThat(UaDates.timeRange(LocalTime.of(9, 40), LocalTime.of(10, 40))).isEqualTo("09:40–10:40");
        assertThat(UaDates.timeRange(null, LocalTime.NOON)).isEmpty();
        assertThat(UaDates.parity(Parity.DENOMINATOR)).isEqualTo("знаменник");
        assertThat(UaDates.parity(Parity.BOTH)).isNull();
        assertThat(UaDates.monthYear(2026, 11)).isEqualTo("листопад 2026");
    }

    @Test
    void ukrainianPlurals() {
        assertThat(List.of(1, 2, 5, 11, 12, 21, 22, 25, 111)).extracting(n -> n + " " + Plurals.of(n, "пара", "пари", "пар"))
                .containsExactly("1 пара", "2 пари", "5 пар", "11 пар", "12 пар", "21 пара", "22 пари", "25 пар",
                        "111 пар");
    }

    @Test
    void linksOnlyForPublicHosts() {
        WebLinks local = new WebLinks(urls("http://localhost:5180"));
        assertThat(local.changes(LocalDate.of(2026, 10, 19))).isEmpty();
        WebLinks production = new WebLinks(urls("https://rozklad.vtc.edu.ua/"));
        assertThat(production.changes(LocalDate.of(2026, 10, 19)))
                .isEqualTo("\n<a href=\"https://rozklad.vtc.edu.ua/changes?date=2026-10-19\">Відкрити заміни на сайті</a>");
        assertThat(WebLinks.isPublic(URI.create("http://127.0.0.1:8088"))).isFalse();
        assertThat(WebLinks.isPublic(URI.create("http://app.localhost"))).isFalse();
    }

    private static ServiceUrlsProperties urls(String web) {
        return new ServiceUrlsProperties(URI.create("http://core"), URI.create("http://importer"), URI.create(web));
    }
}
