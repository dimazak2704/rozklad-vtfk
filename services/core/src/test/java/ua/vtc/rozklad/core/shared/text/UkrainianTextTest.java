package ua.vtc.rozklad.core.shared.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.vtc.rozklad.contracts.Parity;

class UkrainianTextTest {

    @ParameterizedTest
    @CsvSource({"1, пара", "2, пари", "4, пари", "5, пар", "11, пар", "14, пар", "21, пара", "22, пари", "0, пар"})
    void pluralFormsFollowUkrainianRules(long count, String expected) {
        assertThat(UkrainianText.plural(count, "пара", "пари", "пар")).isEqualTo(expected);
    }

    @Test
    void orderHeaderWordsAreUppercaseWithTheCollegeApostrophe() {
        assertThat(UkrainianText.weekdayUpper(DayOfWeek.MONDAY)).isEqualTo("ПОНЕДІЛОК");
        assertThat(UkrainianText.weekdayUpper(DayOfWeek.FRIDAY)).isEqualTo("П'ЯТНИЦЯ");
        assertThat(UkrainianText.parityUpper(Parity.DENOMINATOR)).isEqualTo("ЗНАМЕННИК");
        assertThat(UkrainianText.parityUpper(Parity.NUMERATOR)).isEqualTo("ЧИСЕЛЬНИК");
    }

    @Test
    void datesUseTheGenitiveMonth() {
        assertThat(UkrainianText.dayMonthYear(LocalDate.of(2026, 10, 19))).isEqualTo("19 жовтня 2026");
        assertThat(UkrainianText.dayMonth(LocalDate.of(2026, 5, 22))).isEqualTo("22 травня");
    }

    @Test
    void collatorSortsGroupCodesAlphabetically() {
        List<String> sorted = Stream.of("1ТК1", "1ОП2", "1КІ2", "1М", "1ЕА2")
                .sorted(UkrainianText.collator()).toList();

        assertThat(sorted).containsExactly("1ЕА2", "1КІ2", "1М", "1ОП2", "1ТК1");
    }

    @Test
    void pluralFormsOfPairsAndTimes() {
        assertThat(UkrainianText.pairs(1)).isEqualTo("1 пара");
        assertThat(UkrainianText.pairs(3)).isEqualTo("3 пари");
        assertThat(UkrainianText.pairs(11)).isEqualTo("11 пар");
        assertThat(UkrainianText.pairs(21)).isEqualTo("21 пара");
        assertThat(UkrainianText.times(3)).isEqualTo("3 рази");
        assertThat(UkrainianText.times(5)).isEqualTo("5 разів");
    }

    @Test
    void scheduleWordsUseTheUkrainianApostrophe() {
        assertThat(UkrainianText.weekdayLower(3)).isEqualTo("середа");
        assertThat(UkrainianText.weekdayLabel(5)).isEqualTo("ПʼЯТНИЦЯ");
        assertThat(UkrainianText.date(LocalDate.of(2026, 10, 19))).isEqualTo("19.10.2026");
        assertThat(UkrainianText.parity(Parity.DENOMINATOR)).isEqualTo("знаменник");
    }
}
