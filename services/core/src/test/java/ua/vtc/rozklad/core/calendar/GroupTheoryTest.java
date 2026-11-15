package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.calendar;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.customKind;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.holiday;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.onlineForCourses;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.practice;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Theory days of a group (owner's rule 15.11.2026) in the semester 01.09–30.12.2026: week 1 starts on Monday 31.08
 * (outside the semester), numerator; week 18 is 28.12–30.12, denominator.
 */
class GroupTheoryTest {

    private static final GroupScope KI2 = new GroupScope(UUID.randomUUID(), 4);
    private static final GroupScope VE1 = new GroupScope(UUID.randomUUID(), 1);
    private static final PeriodKindInfo NS = customKind("НС", "Національний спротив", PeriodEffect.SUSPEND);

    @Test
    void withoutPeriodsEveryWeekdayOfTheSemesterCounts() {
        GroupTheory theory = calendar(List.of()).theory(CalendarFixtures.SEMESTER_ID, KI2);

        assertThat(theory.semesterWeeks()).isEqualTo(18);
        assertThat(theory.theoryWeeks()).isEqualTo(18);
        assertThat(theory.numeratorWeeks()).isEqualTo(9);
        assertThat(theory.denominatorWeeks()).isEqualTo(9);
        // 4 дні першого тижня (з вівторка) + 16 повних + 3 дні останнього (пн–ср)
        assertThat(theory.studyDays()).isEqualTo(4 + 16 * 5 + 3);
        assertThat(theory.days(1, Parity.NUMERATOR)).as("Mondays of odd weeks: 31.08 is before the start").isEqualTo(8);
        assertThat(theory.days(1, Parity.BOTH)).isEqualTo(17);
        assertThat(theory.days(3, Parity.BOTH)).isEqualTo(18);
        assertThat(theory.days(5, Parity.DENOMINATOR)).isEqualTo(8);
        assertThat(theory.offPeriods()).isEmpty();
    }

    /** НС 30.11–11.12 of 4КІ2 takes weeks 14 (denominator) and 15 (numerator): 16 weeks of theory of 18. */
    @Test
    void nationalResistanceBlockTakesItsTwoWeeks() {
        StudyPeriod ns = groupPeriod(NS, "Основи національного спротиву", LocalDate.of(2026, 11, 30),
                LocalDate.of(2026, 12, 11), KI2.groupId());

        GroupTheory theory = calendar(List.of(ns)).theory(CalendarFixtures.SEMESTER_ID, KI2);

        assertThat(theory.theoryWeeks()).isEqualTo(16);
        assertThat(theory.numeratorWeeks()).isEqualTo(8);
        assertThat(theory.denominatorWeeks()).isEqualTo(8);
        assertThat(theory.studyDays()).isEqualTo(87 - 10);
        assertThat(theory.days(1, Parity.BOTH)).isEqualTo(15);
        assertThat(theory.days(3, Parity.NUMERATOR)).isEqualTo(8);
        assertThat(theory.offPeriods()).containsExactly(ns);
        assertThat(calendar(List.of(ns)).theory(CalendarFixtures.SEMESTER_ID, VE1).theoryWeeks())
                .as("another group keeps its weeks").isEqualTo(18);
    }

    /** A holiday for everybody (DAY_OFF, scope ALL) and the training camp of the 1st course both take the week. */
    @Test
    void holidaysForAllAndCourseBlocksCount() {
        StudyPeriod winter = holiday("Канікули", LocalDate.of(2026, 12, 28), LocalDate.of(2026, 12, 30));
        StudyPeriod camp = new StudyPeriod(customKind("ЗУ", "Збори", PeriodEffect.SUSPEND), "Збори",
                LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 25), null, PeriodScope.COURSES, Set.of(1), Set.of());
        StudyCalendar calendar = calendar(List.of(winter, camp));

        GroupTheory first = calendar.theory(CalendarFixtures.SEMESTER_ID, VE1);
        GroupTheory fourth = calendar.theory(CalendarFixtures.SEMESTER_ID, KI2);

        assertThat(first.theoryWeeks()).as("1st course: weeks 1–16, as the graph's 16").isEqualTo(16);
        assertThat(first.offPeriods()).containsExactly(camp, winter);
        assertThat(fourth.theoryWeeks()).isEqualTo(17);
        assertThat(fourth.offPeriods()).containsExactly(winter);
    }

    /** Online study and practice of another group are no reason to leave theory; a partial block leaves the week. */
    @Test
    void onlineStudyKeepsTheDaysAndAPartialBlockKeepsItsWeek() {
        StudyPeriod online = onlineForCourses("4КІ навчаються дистанційно", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 31), 4);
        StudyPeriod otherPractice = practice("Практика 2КІ3", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 30),
                UUID.randomUUID());
        StudyPeriod twoDays = practice("Практика", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 20),
                KI2.groupId());

        GroupTheory theory = calendar(List.of(online, otherPractice, twoDays)).theory(CalendarFixtures.SEMESTER_ID, KI2);

        assertThat(theory.theoryWeeks()).isEqualTo(18);
        assertThat(theory.studyDays()).isEqualTo(87 - 2);
        assertThat(theory.offPeriods()).containsExactly(twoDays);
    }

    /** A parity override makes week 2 a numerator week: its days count as numerator days. */
    @Test
    void parityOverrideDecidesTheWeekOfADay() {
        StudyCalendar calendar = calendar(List.of(), Map.of(LocalDate.of(2026, 9, 7), Parity.NUMERATOR));

        GroupTheory theory = calendar.theory(CalendarFixtures.SEMESTER_ID, KI2);

        assertThat(theory.numeratorWeeks()).isEqualTo(10);
        assertThat(theory.denominatorWeeks()).isEqualTo(8);
        assertThat(theory.days(1, Parity.NUMERATOR)).isEqualTo(9);
    }

    @Test
    void semesterOutsideTheCalendarIsAnError() {
        assertThatThrownBy(() -> calendar(List.of()).theory(UUID.randomUUID(), KI2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StudyPeriod groupPeriod(PeriodKindInfo kind, String title, LocalDate from, LocalDate to,
                                           UUID group) {
        return new StudyPeriod(kind, title, from, to, null, PeriodScope.GROUPS, Set.of(), Set.of(group));
    }
}
