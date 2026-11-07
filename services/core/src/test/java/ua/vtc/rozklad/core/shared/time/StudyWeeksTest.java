package ua.vtc.rozklad.core.shared.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;

class StudyWeeksTest {

    private static final LocalDate SEMESTER_START = LocalDate.of(2026, 9, 1);

    @Test
    void weekOfOrder35IsTheEighthWeekAndADenominator() {
        LocalDate order35 = LocalDate.of(2026, 10, 19);

        assertThat(StudyWeeks.weekNumber(SEMESTER_START, order35)).isEqualTo(8);
        assertThat(StudyWeeks.parity(SEMESTER_START, Parity.NUMERATOR, order35, null)).isEqualTo(Parity.DENOMINATOR);
    }

    @Test
    void weekOneStartsOnTheMondayBeforeATuesdayStart() {
        assertThat(StudyWeeks.weekStart(SEMESTER_START)).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(StudyWeeks.weekNumber(SEMESTER_START, LocalDate.of(2026, 9, 4))).isEqualTo(1);
        assertThat(StudyWeeks.weekNumber(SEMESTER_START, LocalDate.of(2026, 9, 7))).isEqualTo(2);
        assertThat(StudyWeeks.parity(SEMESTER_START, Parity.NUMERATOR, LocalDate.of(2026, 9, 4), null))
                .isEqualTo(Parity.NUMERATOR);
    }

    @Test
    void firstWeekParityCanBeTheDenominator() {
        assertThat(StudyWeeks.parity(SEMESTER_START, Parity.DENOMINATOR, LocalDate.of(2026, 10, 19), null))
                .isEqualTo(Parity.NUMERATOR);
    }

    @Test
    void manualOverrideWins() {
        assertThat(StudyWeeks.parity(SEMESTER_START, Parity.NUMERATOR, LocalDate.of(2026, 10, 19), Parity.NUMERATOR))
                .isEqualTo(Parity.NUMERATOR);
    }

    @Test
    void firstWeekParityMustBeAWeekParity() {
        assertThatThrownBy(() -> StudyWeeks.parity(SEMESTER_START, Parity.BOTH, SEMESTER_START, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lessonsAreOnWeekdaysOnly() {
        assertThat(StudyWeeks.isWeekday(LocalDate.of(2026, 10, 23))).isTrue();
        assertThat(StudyWeeks.isWeekday(LocalDate.of(2026, 10, 24))).isFalse();
        assertThat(StudyWeeks.isWeekday(LocalDate.of(2026, 10, 25))).isFalse();
    }
}
