package ua.vtc.rozklad.core.load.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.calendar;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.customKind;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.holiday;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.practice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.CalendarFixtures;
import ua.vtc.rozklad.core.calendar.GroupScope;
import ua.vtc.rozklad.core.calendar.GroupTheory;
import ua.vtc.rozklad.core.calendar.PeriodEffect;
import ua.vtc.rozklad.core.calendar.PeriodScope;
import ua.vtc.rozklad.core.calendar.StudyPeriod;
import ua.vtc.rozklad.core.load.internal.LoadPlan.Slot;

/**
 * Planned hours by the owner's rule of 15.11.2026 in the semester 01.09–30.12.2026 (18 weeks, week 1 a numerator
 * week from Tuesday 01.09, week 18 a denominator week to Wednesday 30.12). The real case: 4КІ2 ДК, 3 h a week
 * (Monday every week + Wednesday numerator), НС 30.11–11.12: the old plan 3 × 18 = 54 could never be reached.
 */
class LoadPlanTest {

    private static final GroupScope KI2 = new GroupScope(UUID.randomUUID(), 4);
    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private static final List<Slot> MONDAY_AND_WEDNESDAY_NUMERATOR = List.of(new Slot(0, 1, Parity.BOTH),
            new Slot(0, 3, Parity.NUMERATOR));

    @Test
    void nationalResistanceWeeksAreNotPlanned() {
        GroupTheory theory = theory(ns(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 11)));

        // понеділки: 17 − 2 тижні НС = 15; середи чисельника: 9 − 1 (тиждень 15) = 8 → 23 пари = 46 год
        assertThat(LoadPlan.plannedHours(THREE, theory, MONDAY_AND_WEDNESDAY_NUMERATOR)).isEqualTo(46);
        assertThat(LoadPlan.plannedHours(THREE, theory(), MONDAY_AND_WEDNESDAY_NUMERATOR))
                .as("the same discipline without НС: 17 Mondays + 9 numerator Wednesdays").isEqualTo(52);
    }

    @Test
    void practiceBlockOfTheGroupIsNotPlanned() {
        GroupTheory theory = theory(practice("Навчальна практика", LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 2),
                KI2.groupId()));

        // тижні 4 (знаменник) і 5 (чисельник): 15 понеділків + 8 серед чисельника
        assertThat(LoadPlan.plannedHours(THREE, theory, MONDAY_AND_WEDNESDAY_NUMERATOR)).isEqualTo(46);
    }

    /** A numerator-only pair (1 h a week) loses only the numerator weeks the group is off theory. */
    @Test
    void numeratorOnlyDisciplineMissesOnlyNumeratorWeeks() {
        List<Slot> numeratorTuesday = List.of(new Slot(0, 2, Parity.NUMERATOR));
        BigDecimal one = BigDecimal.ONE;

        assertThat(LoadPlan.plannedHours(one, theory(), numeratorTuesday)).as("9 numerator Tuesdays").isEqualTo(18);
        assertThat(LoadPlan.plannedHours(one, theory(ns(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11))),
                numeratorTuesday)).as("a denominator week off: nothing lost").isEqualTo(18);
        assertThat(LoadPlan.plannedHours(one, theory(ns(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 18))),
                numeratorTuesday)).as("a numerator week off: one pair less").isEqualTo(16);
    }

    /** Winter holidays for everybody close the last week: its Monday–Wednesday pairs are not planned. */
    @Test
    void holidayForEverybodyIsNotPlanned() {
        GroupTheory theory = theory(holiday("Канікули", LocalDate.of(2026, 12, 28), LocalDate.of(2026, 12, 30)));

        assertThat(LoadPlan.plannedHours(THREE, theory, MONDAY_AND_WEDNESDAY_NUMERATOR))
                .as("16 Mondays + 9 numerator Wednesdays (week 18 is a denominator week)").isEqualTo(50);
        assertThat(LoadPlan.plannedHours(BigDecimal.valueOf(2), theory, List.of(new Slot(0, 4, Parity.BOTH))))
                .as("Thursday 31.12 is outside the semester anyway").isEqualTo(34);
    }

    /** Without the timetable the weekdays and the parity are unknown: a week counts by its study days. */
    @Test
    void withoutLessonsTheWeekCountsByItsStudyDays() {
        assertThat(LoadPlan.plannedHours(THREE, theory(), List.of())).as("3 × 87 / 5 = 52.2").isEqualTo(52);
        assertThat(LoadPlan.plannedHours(THREE, theory(ns(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 11))),
                List.of())).as("3 × 77 / 5 = 46.2").isEqualTo(46);
    }

    /** The curriculum's hours rule the plan; the timetable only says on which days its pairs fall. */
    @Test
    void loadHoursOtherThanTheTimetablesAreScaled() {
        GroupTheory theory = theory(ns(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 11)));

        // 4 год, а розклад дає 3: 23 пари × 4/3 = 30,7 пари → 61 год
        assertThat(LoadPlan.plannedHours(BigDecimal.valueOf(4), theory, MONDAY_AND_WEDNESDAY_NUMERATOR)).isEqualTo(61);
    }

    /** A split item counts its busiest subgroup, as accounting reports the cohort with the largest forecast. */
    @Test
    void splitItemTakesItsBusiestSubgroup() {
        GroupTheory theory = theory(ns(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 11)));
        List<Slot> slots = List.of(new Slot(1, 2, Parity.NUMERATOR), new Slot(2, 5, Parity.DENOMINATOR));

        // підгрупа 1: вівторки чисельника 9 − 1 = 8; підгрупа 2: п'ятниці знаменника 8 − 1 = 7 (01.01 поза семестром)
        assertThat(LoadPlan.plannedHours(BigDecimal.ONE, theory, slots)).isEqualTo(16);
    }

    private static StudyPeriod ns(LocalDate from, LocalDate to) {
        return new StudyPeriod(customKind("НС", "Національний спротив", PeriodEffect.SUSPEND),
                "Основи національного спротиву", from, to, null, PeriodScope.GROUPS, Set.of(), Set.of(KI2.groupId()));
    }

    private static GroupTheory theory(StudyPeriod... periods) {
        return calendar(List.of(periods)).theory(CalendarFixtures.SEMESTER_ID, KI2);
    }
}
