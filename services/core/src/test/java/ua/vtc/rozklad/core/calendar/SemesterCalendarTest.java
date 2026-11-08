package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.vtc.rozklad.contracts.Parity;

/** Week numbering and parity of DOMAIN §2, checked against the dates printed in real orders. */
class SemesterCalendarTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 30);

    private final SemesterCalendar semester = new SemesterCalendar(START, END, Parity.NUMERATOR, Map.of());

    @Test
    void october19of2026IsWeek8Denominator() {
        // розпорядження №35: «На 19 жовтня 2026 р. ПОНЕДІЛОК - ЗНАМЕННИК»
        LocalDate order35 = LocalDate.of(2026, 10, 19);

        assertThat(semester.weekNumber(order35)).isEqualTo(8);
        assertThat(semester.parity(order35)).isEqualTo(Parity.DENOMINATOR);
    }

    @ParameterizedTest(name = "{0} → week {1}, {2}")
    @CsvSource({
            "2026-09-01, 1, NUMERATOR",     // вівторок: семестр починається посеред тижня
            "2026-09-06, 1, NUMERATOR",     // неділя того самого тижня
            "2026-09-07, 2, DENOMINATOR",
            "2026-10-23, 8, DENOMINATOR",   // п'ятниця 8-го тижня
            "2026-10-26, 9, NUMERATOR",
            "2026-12-30, 18, DENOMINATOR"   // останній день семестру
    })
    void weeksAreMondayBasedAndAlternate(LocalDate date, int week, Parity parity) {
        assertThat(semester.weekNumber(date)).isEqualTo(week);
        assertThat(semester.parity(date)).isEqualTo(parity);
    }

    @Test
    void weekOneStartsOnTheMondayBeforeAMidweekStart() {
        assertThat(semester.firstWeekStart()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(semester.contains(LocalDate.of(2026, 8, 31))).isFalse();
        assertThat(semester.contains(START)).isTrue();
        assertThat(semester.contains(END)).isTrue();
        assertThat(semester.contains(END.plusDays(1))).isFalse();
    }

    @Test
    void overrideWinsForItsWeekOnly() {
        SemesterCalendar withOverride = new SemesterCalendar(START, END, Parity.NUMERATOR,
                Map.of(LocalDate.of(2026, 10, 19), Parity.NUMERATOR));

        assertThat(withOverride.parity(LocalDate.of(2026, 10, 21))).isEqualTo(Parity.NUMERATOR);
        assertThat(withOverride.isOverridden(LocalDate.of(2026, 10, 21))).isTrue();
        // сусідні тижні рахуються за правилом чергування
        assertThat(withOverride.parity(LocalDate.of(2026, 10, 12))).isEqualTo(Parity.NUMERATOR);
        assertThat(withOverride.parity(LocalDate.of(2026, 10, 26))).isEqualTo(Parity.NUMERATOR);
        assertThat(withOverride.isOverridden(LocalDate.of(2026, 10, 26))).isFalse();
    }

    @Test
    void firstWeekDenominatorFlipsTheAlternation() {
        SemesterCalendar denominatorFirst = new SemesterCalendar(START, END, Parity.DENOMINATOR, Map.of());

        assertThat(denominatorFirst.parity(START)).isEqualTo(Parity.DENOMINATOR);
        assertThat(denominatorFirst.parity(LocalDate.of(2026, 10, 19))).isEqualTo(Parity.NUMERATOR);
    }

    @Test
    void weeksListCoversEveryWeekWithComputedAndEffectiveParity() {
        SemesterCalendar withOverride = new SemesterCalendar(START, END, Parity.NUMERATOR,
                Map.of(LocalDate.of(2026, 10, 19), Parity.NUMERATOR));

        List<SemesterCalendar.Week> weeks = withOverride.weeks();

        assertThat(weeks).hasSize(18);
        assertThat(weeks.getFirst().weekStart()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(weeks.getLast().weekStart()).isEqualTo(LocalDate.of(2026, 12, 28));
        SemesterCalendar.Week week8 = weeks.get(7);
        assertThat(week8.number()).isEqualTo(8);
        assertThat(week8.weekEnd()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(week8.computedParity()).isEqualTo(Parity.DENOMINATOR);
        assertThat(week8.parity()).isEqualTo(Parity.NUMERATOR);
        assertThat(week8.overridden()).isTrue();
        assertThat(withOverride.calendarWeeks()).isEqualTo(18);
    }

    @Test
    void datesBeforeTheFirstWeekHaveNoNumber() {
        assertThatThrownBy(() -> semester.weekNumber(LocalDate.of(2026, 8, 30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parityBothIsNotAWeekParity() {
        assertThatThrownBy(() -> new SemesterCalendar(START, END, Parity.BOTH, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemesterCalendar.opposite(Parity.BOTH)).isInstanceOf(IllegalArgumentException.class);
    }
}
