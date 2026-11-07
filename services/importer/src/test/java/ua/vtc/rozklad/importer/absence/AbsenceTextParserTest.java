package ua.vtc.rozklad.importer.absence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;

/** Today is Saturday 7.11.2026 in all cases. */
class AbsenceTextParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    private final AbsenceTextParser parser = new AbsenceTextParser();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            сьогодні не буду                       | 2026-11-07 | 2026-11-07
            Завтра мене не буде                    | 2026-11-08 | 2026-11-08
            післязавтра на конференції             | 2026-11-09 | 2026-11-09
            4.11 не буду, лікарняний               | 2026-11-04 | 2026-11-04
            04.11.2026 відпустка                   | 2026-11-04 | 2026-11-04
            з 4 по 6 листопада у відрядженні       | 2026-11-04 | 2026-11-06
            9-11 листопада відпустка               | 2026-11-09 | 2026-11-11
            з 10.11 до 12.11 лікарняний            | 2026-11-10 | 2026-11-12
            12 грудня не буду                      | 2026-12-12 | 2026-12-12
            у понеділок не прийду                  | 2026-11-09 | 2026-11-09
            в середу і четвер відпустка            | 2026-11-11 | 2026-11-12
            1.09 не буду                           | 2027-09-01 | 2027-09-01
            """)
    void dates(String text, LocalDate from, LocalDate to) {
        ParsedAbsence parsed = parser.parse(text, TODAY);
        assertThat(parsed.dateFrom()).isEqualTo(from);
        assertThat(parsed.dateTo()).isEqualTo(to);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            завтра 1-2 пари не буду               | 1,2
            завтра з 1 по 3 пару                   | 1,2,3
            сьогодні 2 і 3 пари                    | 2,3
            завтра на першій парі не буду         | 1
            в п'ятницю першу і другу пари         | 1,2
            сьогодні 4 пару                        | 4
            """)
    void pairs(String text, String expected) {
        List<Integer> pairs = parser.parse(text, TODAY).pairs();
        assertThat(pairs).containsExactlyElementsOf(java.util.Arrays.stream(expected.split(","))
                .map(Integer::valueOf).toList());
    }

    @Test
    void wholeDayWhenNoPairsOrSaidSo() {
        assertThat(parser.parse("4.11 не буду, лікарняний", TODAY).pairs()).isNull();
        assertThat(parser.parse("завтра весь день на лікарняному", TODAY).pairs()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            завтра лікарняний              | SICK_LEAVE
            сьогодні захворів              | SICK_LEAVE
            приболів, завтра не буду       | SICK_LEAVE
            з 4 по 6 листопада відпустка   | VACATION
            завтра відрядження до Києва    | BUSINESS_TRIP
            завтра сімейні обставини       | PERSONAL
            завтра не буду                 | OTHER
            """)
    void reasons(String text, AbsenceReasonCategory expected) {
        assertThat(parser.parse(text, TODAY).reasonCategory()).isEqualTo(expected);
    }

    @Test
    void confidentWhenDateAndReasonAreExplicit() {
        ParsedAbsence parsed = parser.parse("4.11 не буду, лікарняний", TODAY);
        assertThat(parsed.confidence()).isEqualTo(1.0);
        assertThat(parsed.clarification()).isNull();
        assertThat(parsed.source()).isEqualTo(ParsedAbsence.Source.RULES);
    }

    @Test
    void unsureWithoutDateAndAsksToClarify() {
        ParsedAbsence parsed = parser.parse("не зможу провести пари", TODAY);
        assertThat(parsed.dateFrom()).isEqualTo(TODAY);
        assertThat(parsed.confidence()).isLessThan(ParseAbsenceService.AI_THRESHOLD);
        assertThat(parsed.clarification()).contains("дату");
    }

    @Test
    void reversedRangeIsSwappedWithLowerConfidence() {
        ParsedAbsence parsed = parser.parse("з 6.11 по 4.11 лікарняний", TODAY);
        assertThat(parsed.dateFrom()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(parsed.dateTo()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(parsed.confidence()).isLessThan(1.0);
    }
}
