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

    // ───────── r3-ai-imports-03, r4-ai-imports-55: what one range with one set of pairs cannot carry ─────────

    /** Sunday 8.11.2026, the day the findings were reproduced. */
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 8);

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            з понеділка по середу на лікарняному   | 2026-11-09 | 2026-11-11
            від вівторка до четверга відпустка     | 2026-11-10 | 2026-11-12
            понеділок-середа лікарняний            | 2026-11-09 | 2026-11-11
            не буду 11 і 12 листопада, лікарняний  | 2026-11-11 | 2026-11-12
            11, 12 та 13 листопада відрядження     | 2026-11-11 | 2026-11-13
            1 січня 2030 лікарняний                | 2030-01-01 | 2030-01-01
            з 5 по 7 січня 2027 року відпустка     | 2027-01-05 | 2027-01-07
            у п'ятницю й понеділок лікарняний      | 2026-11-13 | 2026-11-16
            """)
    void moreDateForms(String text, LocalDate from, LocalDate to) {
        ParsedAbsence parsed = parser.parse(text, SUNDAY);
        assertThat(parsed.dateFrom()).isEqualTo(from);
        assertThat(parsed.dateTo()).isEqualTo(to);
    }

    @Test
    void weekdayRangeEndsAfterItsStartEvenMidWeek() {
        // четвер 12.11: «з понеділка по середу» — наступний тиждень, а не «сьогодні … понеділок»
        ParsedAbsence parsed = parser.parse("з понеділка по середу лікарняний", LocalDate.of(2026, 11, 12));
        assertThat(parsed.dateFrom()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(parsed.dateTo()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(parsed.confidence()).isEqualTo(1.0);
    }

    @Test
    void explicitFarYearIsKeptAndAskedAbout() {
        ParsedAbsence parsed = parser.parse("1 січня 2030 лікарняний", SUNDAY);
        assertThat(parsed.dateFrom()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(parsed.clarification()).isEqualTo("Перевірте, будь ласка, дати відсутності.");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            завтра 9 пару не проведу
            завтра 0 пару не проведу
            завтра 1-9 пари
            завтра 2 і 12 пари
            """)
    void pairNumbersOutsideTheDayAreAskedAboutNotTurnedIntoAWholeDay(String text) {
        AbsenceTextParser.Reading reading = parser.read(text, SUNDAY);
        assertThat(reading.settled()).isTrue();
        assertThat(reading.parsed().confidence()).isLessThan(0.6);
        assertThat(reading.parsed().clarification()).contains("від 1 до 8");
    }

    @Test
    void differentPairsOnDifferentDaysAreAskedAbout() {
        AbsenceTextParser.Reading reading = parser.read("10 листопада 3 пара, 11 листопада 1 пара", SUNDAY);
        assertThat(reading.settled()).isTrue();
        assertThat(reading.parsed().confidence()).isLessThan(0.6);
        assertThat(reading.parsed().clarification()).contains("кожен день окремим повідомленням");
    }

    @Test
    void samePairsOnSeveralDaysOrSeveralMentionsOnOneDayAreFine() {
        ParsedAbsence days = parser.parse("10 і 11 листопада 1-2 пари, відрядження", SUNDAY);
        assertThat(days.pairs()).containsExactly(1, 2);
        assertThat(days.clarification()).isNull();
        ParsedAbsence oneDay = parser.parse("завтра 1 пара і 3 пара, лікарняний", SUNDAY);
        assertThat(oneDay.pairs()).containsExactly(1, 3);
        assertThat(oneDay.clarification()).isNull();
        assertThat(oneDay.confidence()).isEqualTo(1.0);
    }

    @Test
    void separateDaysWithAStudyDayBetweenAreAskedAbout() {
        AbsenceTextParser.Reading reading = parser.read("4.11 і 6.11 лікарняний", LocalDate.of(2026, 11, 2));
        assertThat(reading.settled()).isTrue();
        assertThat(reading.parsed().clarification()).contains("Між названими днями");
        // вихідні між днями — не прогалина
        ParsedAbsence overWeekend = parser.parse("у п'ятницю й понеділок лікарняний", SUNDAY);
        assertThat(overWeekend.clarification()).isNull();
    }
}
