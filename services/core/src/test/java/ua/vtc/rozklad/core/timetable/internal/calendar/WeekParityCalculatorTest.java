package ua.vtc.rozklad.core.timetable.internal.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;

class WeekParityCalculatorTest {

    private static final LocalDate SEMESTER_START = LocalDate.of(2026, 9, 1);

    @Test
    void weekOfOrder35IsTheEighthDenominatorWeek() {
        // DOMAIN.md §2: початок 01.09.2026 (вівторок) ⇒ тиждень 19.10.2026 восьмий ⇒ ЗНАМЕННИК (розпорядження №35)
        WeekParityCalculator weeks = new WeekParityCalculator(SEMESTER_START, Parity.NUMERATOR, Map.of());

        assertThat(weeks.weekNumber(LocalDate.of(2026, 10, 19))).isEqualTo(8);
        assertThat(weeks.parity(LocalDate.of(2026, 10, 19))).isEqualTo(Parity.DENOMINATOR);
        assertThat(weeks.parity(LocalDate.of(2026, 10, 23))).isEqualTo(Parity.DENOMINATOR);
    }

    @Test
    void firstWeekStartsOnTheMondayBeforeTheSemesterStart() {
        WeekParityCalculator weeks = new WeekParityCalculator(SEMESTER_START, Parity.NUMERATOR, Map.of());

        assertThat(weeks.weekNumber(LocalDate.of(2026, 8, 31))).isEqualTo(1);
        assertThat(weeks.parity(LocalDate.of(2026, 9, 4))).isEqualTo(Parity.NUMERATOR);
        assertThat(weeks.weekNumber(LocalDate.of(2026, 9, 7))).isEqualTo(2);
        assertThat(weeks.parity(LocalDate.of(2026, 9, 7))).isEqualTo(Parity.DENOMINATOR);
    }

    @Test
    void overrideOfAWeekWinsOnlyForThatWeek() {
        WeekParityCalculator weeks = new WeekParityCalculator(SEMESTER_START, Parity.NUMERATOR,
                Map.of(LocalDate.of(2026, 10, 19), Parity.NUMERATOR));

        assertThat(weeks.parity(LocalDate.of(2026, 10, 21))).isEqualTo(Parity.NUMERATOR);
        assertThat(weeks.parity(LocalDate.of(2026, 10, 26))).isEqualTo(Parity.NUMERATOR);
        assertThat(weeks.parity(LocalDate.of(2026, 11, 2))).isEqualTo(Parity.DENOMINATOR);
    }

    @Test
    void semesterMayStartWithADenominatorWeek() {
        WeekParityCalculator weeks = new WeekParityCalculator(SEMESTER_START, Parity.DENOMINATOR, Map.of());

        assertThat(weeks.parity(LocalDate.of(2026, 9, 1))).isEqualTo(Parity.DENOMINATOR);
        assertThat(weeks.parity(LocalDate.of(2026, 10, 19))).isEqualTo(Parity.NUMERATOR);
    }

    @Test
    void firstWeekParityCannotBeBoth() {
        assertThatThrownBy(() -> new WeekParityCalculator(SEMESTER_START, Parity.BOTH, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
