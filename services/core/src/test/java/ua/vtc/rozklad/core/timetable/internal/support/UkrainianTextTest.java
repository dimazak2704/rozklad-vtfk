package ua.vtc.rozklad.core.timetable.internal.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;

class UkrainianTextTest {

    @Test
    void pluralFormsOfPairsAndTimes() {
        assertThat(UkrainianText.pairs(1)).isEqualTo("1 пара");
        assertThat(UkrainianText.pairs(3)).isEqualTo("3 пари");
        assertThat(UkrainianText.pairs(5)).isEqualTo("5 пар");
        assertThat(UkrainianText.pairs(11)).isEqualTo("11 пар");
        assertThat(UkrainianText.pairs(21)).isEqualTo("21 пара");
        assertThat(UkrainianText.pairs(22)).isEqualTo("22 пари");
        assertThat(UkrainianText.times(3)).isEqualTo("3 рази");
        assertThat(UkrainianText.times(5)).isEqualTo("5 разів");
    }

    @Test
    void calendarWords() {
        assertThat(UkrainianText.weekday(3)).isEqualTo("середа");
        assertThat(UkrainianText.weekdayLabel(5)).isEqualTo("ПʼЯТНИЦЯ");
        assertThat(UkrainianText.date(LocalDate.of(2026, 10, 19))).isEqualTo("19.10.2026");
        assertThat(UkrainianText.parity(Parity.DENOMINATOR)).isEqualTo("знаменник");
    }
}
