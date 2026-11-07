package ua.vtc.rozklad.core.accounting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.accounting.internal.LedgerResult.Suggestion;

class HoursLedgerTest {

    private static final int HOURS_PER_PAIR = 2;
    private static final LocalDate FIRST_MONDAY = LocalDate.of(2026, 9, 7);

    @Test
    void factCountsEndedLessonsAndForecastAddsTheRest() {
        LedgerResult ledger = HoursLedger.compute(20, HOURS_PER_PAIR, weekly(10, 4));

        assertThat(ledger.fact()).isEqualTo(8);
        assertThat(ledger.remaining()).isEqualTo(12);
        assertThat(ledger.forecast()).isEqualTo(20);
        assertThat(ledger.status()).isEqualTo(AccountingStatus.ON_TRACK);
        assertThat(ledger.suggestion()).isNull();
    }

    @Test
    void finishDateIsTheLessonThatReachesThePlan() {
        LedgerResult ledger = HoursLedger.compute(12, HOURS_PER_PAIR, weekly(10, 2));

        // 6 пар по 2 год: план досягнуто на шостому тижні
        assertThat(ledger.forecastFinishDate()).isEqualTo(FIRST_MONDAY.plusWeeks(5));
        assertThat(ledger.status()).isEqualTo(AccountingStatus.WILL_OVERRUN);
    }

    @Test
    void overrunSuggestsFinishingTheFutureLessonsAfterThePlan() {
        List<LedgerLesson> lessons = weekly(10, 2);

        Suggestion suggestion = HoursLedger.compute(12, HOURS_PER_PAIR, lessons).suggestion();

        assertThat(suggestion.kind()).isEqualTo(Suggestion.Kind.DISCIPLINE_FINISHED);
        assertThat(suggestion.lessons()).isEqualTo(4);
        assertThat(suggestion.fromDate()).isEqualTo(FIRST_MONDAY.plusWeeks(6));
        assertThat(suggestion.lessonIds()).containsExactlyElementsOf(
                lessons.subList(6, 10).stream().map(LedgerLesson::lessonId).toList());
        assertThat(suggestion.text()).isEqualTo("Вичитано 12 жовтня: зняти 4 пари після цієї дати");
    }

    @Test
    void finishedWhenTheFactReachesThePlan() {
        LedgerResult ledger = HoursLedger.compute(8, HOURS_PER_PAIR, weekly(6, 5));

        assertThat(ledger.status()).isEqualTo(AccountingStatus.FINISHED);
        // уже минулі пари понад план змінити не можна — пропонуємо зняти лише майбутню
        assertThat(ledger.suggestion().lessons()).isEqualTo(1);
    }

    @Test
    void underrunSuggestsAdditionalPairs() {
        LedgerResult ledger = HoursLedger.compute(30, HOURS_PER_PAIR, weekly(10, 3));

        assertThat(ledger.forecast()).isEqualTo(20);
        assertThat(ledger.status()).isEqualTo(AccountingStatus.WILL_UNDERRUN);
        assertThat(ledger.forecastFinishDate()).isNull();
        assertThat(ledger.suggestion().kind()).isEqualTo(Suggestion.Kind.ADDITIONAL);
        assertThat(ledger.suggestion().lessons()).isEqualTo(5);
        assertThat(ledger.suggestion().text()).isEqualTo("Не вистачає 10 год: додати 5 пар");
    }

    @Test
    void onePairOfDifferenceIsTolerated() {
        assertThat(HoursLedger.compute(18, HOURS_PER_PAIR, weekly(10, 0)).status()).isEqualTo(AccountingStatus.ON_TRACK);
        assertThat(HoursLedger.compute(22, HOURS_PER_PAIR, weekly(10, 0)).status()).isEqualTo(AccountingStatus.ON_TRACK);
        assertThat(HoursLedger.compute(17, HOURS_PER_PAIR, weekly(10, 0)).status())
                .isEqualTo(AccountingStatus.WILL_OVERRUN);
        assertThat(HoursLedger.compute(23, HOURS_PER_PAIR, weekly(10, 0)).status())
                .isEqualTo(AccountingStatus.WILL_UNDERRUN);
    }

    @Test
    void splitDisciplineIsCountedForTheBusiestCohort() {
        List<LedgerLesson> lessons = new ArrayList<>();
        lessons.add(lesson(FIRST_MONDAY, 1, 0, true));
        lessons.add(lesson(FIRST_MONDAY, 2, 1, true));
        lessons.add(lesson(FIRST_MONDAY, 2, 2, true));
        lessons.add(lesson(FIRST_MONDAY.plusDays(1), 3, 1, false));

        LedgerResult ledger = HoursLedger.compute(10, HOURS_PER_PAIR, lessons);

        // підгрупа 1: спільна пара + 2 свої; підгрупа 2: спільна + 1
        assertThat(ledger.fact()).isEqualTo(4);
        assertThat(ledger.remaining()).isEqualTo(2);
    }

    @Test
    void zeroPlanMeansEveryFutureLessonIsBeyondIt() {
        LedgerResult ledger = HoursLedger.compute(0, HOURS_PER_PAIR, weekly(3, 1));

        assertThat(ledger.status()).isEqualTo(AccountingStatus.FINISHED);
        assertThat(ledger.forecastFinishDate()).isNull();
        assertThat(ledger.suggestion().lessons()).isEqualTo(2);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> HoursLedger.compute(-1, HOURS_PER_PAIR, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HoursLedger.compute(10, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    /** {@code count} Monday lessons, the first {@code ended} of them already over. */
    private static List<LedgerLesson> weekly(int count, int ended) {
        List<LedgerLesson> lessons = new ArrayList<>();
        for (int week = 0; week < count; week++) {
            lessons.add(lesson(FIRST_MONDAY.plusWeeks(week), 2, 0, week < ended));
        }
        return lessons;
    }

    private static LedgerLesson lesson(LocalDate date, int pair, int subgroup, boolean ended) {
        return new LedgerLesson(UUID.randomUUID(), date, pair, subgroup, ended);
    }
}
