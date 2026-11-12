package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.calendar;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.holiday;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.onlineForCourses;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.practice;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.studyModeForAll;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.studyModeForGroups;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;

class StudyCalendarTest {

    private static final GroupScope GROUP_4KI1 = new GroupScope(UUID.randomUUID(), 4);
    private static final GroupScope GROUP_2KI3 = new GroupScope(UUID.randomUUID(), 2);

    private final StudyCalendar calendar = calendar(List.of(
            holiday("Осінні канікули", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 30)),
            practice("2КІ3 – Електрорадіомонтажна практика", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 30),
                    GROUP_2KI3.groupId()),
            onlineForCourses("Групи 4КІ навчаються дистанційно", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31),
                    4)));

    @Test
    void ordinaryDayOfTheSemester() {
        DayInfo day = calendar.day(LocalDate.of(2026, 11, 9), GROUP_4KI1);

        assertThat(day.studyDay()).isTrue();
        assertThat(day.online()).isFalse();
        assertThat(day.note()).isNull();
        assertThat(day.parity()).isEqualTo(Parity.NUMERATOR);
        assertThat(day.weekNumber()).isEqualTo(11);
        assertThat(day.semesterId()).isEqualTo(CalendarFixtures.SEMESTER_ID);
    }

    @Test
    void holidayClosesTheDayForEverybody() {
        assertThat(calendar.day(LocalDate.of(2026, 10, 27), GROUP_4KI1).studyDay()).isFalse();
        assertThat(calendar.day(LocalDate.of(2026, 10, 27), null))
                .satisfies(day -> {
                    assertThat(day.studyDay()).isFalse();
                    assertThat(day.note()).isEqualTo("Осінні канікули");
                });
    }

    @Test
    void practiceClosesTheDayOnlyForItsGroup() {
        LocalDate date = LocalDate.of(2026, 10, 20);

        assertThat(calendar.day(date, GROUP_2KI3)).satisfies(day -> {
            assertThat(day.studyDay()).isFalse();
            assertThat(day.note()).isEqualTo("2КІ3 – Електрорадіомонтажна практика");
        });
        assertThat(calendar.day(date, null).studyDay()).isTrue();
    }

    @Test
    void studyModeKeepsLessonsButMarksThemOnline() {
        assertThat(calendar.day(LocalDate.of(2026, 10, 20), GROUP_4KI1)).satisfies(day -> {
            assertThat(day.studyDay()).isTrue();
            assertThat(day.online()).isTrue();
            assertThat(day.note()).isEqualTo("Групи 4КІ навчаються дистанційно");
        });
        assertThat(calendar.day(LocalDate.of(2026, 10, 20), GROUP_2KI3).online()).isFalse();
        assertThat(calendar.day(LocalDate.of(2026, 10, 27), GROUP_4KI1).note())
                .isEqualTo("Осінні канікули; Групи 4КІ навчаються дистанційно");
    }

    @Test
    void groupOfflinePeriodInsideCourseOnlinePeriodBringsTheGroupBackToClasses() {
        GroupScope group3x1 = new GroupScope(UUID.randomUUID(), 3);
        GroupScope group3x2 = new GroupScope(UUID.randomUUID(), 3);
        StudyCalendar withException = calendar(List.of(
                onlineForCourses("3 курс навчається дистанційно", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31),
                        3),
                studyModeForGroups("3Х1 повертається на заняття", LocalDate.of(2026, 10, 19),
                        LocalDate.of(2026, 10, 31), StudyMode.OFFLINE, group3x1.groupId())));

        assertThat(withException.day(LocalDate.of(2026, 10, 20), group3x1)).satisfies(day -> {
            assertThat(day.online()).isFalse();
            assertThat(day.note()).isNull();
        });
        assertThat(withException.day(LocalDate.of(2026, 10, 16), group3x1).online()).isTrue();
        assertThat(withException.day(LocalDate.of(2026, 10, 20), group3x2)).satisfies(day -> {
            assertThat(day.online()).isTrue();
            assertThat(day.note()).isEqualTo("3 курс навчається дистанційно");
        });
    }

    @Test
    void laterCollegeWideDecisionOverridesTheEarlierOneButNotACourseException() {
        GroupScope group = new GroupScope(UUID.randomUUID(), 4);
        StudyCalendar decisions = calendar(List.of(
                studyModeForAll("Усі дистанційно", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                        StudyMode.ONLINE),
                studyModeForAll("Усі повертаються", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 31),
                        StudyMode.OFFLINE),
                onlineForCourses("4 курс дистанційно", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 30), 4)));

        assertThat(decisions.day(LocalDate.of(2026, 10, 14), group).online()).isTrue();
        assertThat(decisions.day(LocalDate.of(2026, 10, 20), group).online()).isFalse();
        assertThat(decisions.day(LocalDate.of(2026, 10, 27), group).online()).isTrue();
        assertThat(decisions.day(LocalDate.of(2026, 10, 20), null).online()).isFalse();
    }

    @Test
    void weekendAndDaysOutsideTheSemesterHaveNoLessons() {
        assertThat(calendar.day(LocalDate.of(2026, 10, 24), null).studyDay()).isFalse();
        assertThat(calendar.day(LocalDate.of(2027, 1, 5), null)).satisfies(day -> {
            assertThat(day.studyDay()).isFalse();
            assertThat(day.parity()).isNull();
            assertThat(day.semesterId()).isNull();
        });
    }

    @Test
    void studyDatesSkipWeekendsAndHolidays() {
        assertThat(calendar.studyDates(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 11, 2))).containsExactly(
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21),
                LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23), LocalDate.of(2026, 11, 2));
    }
}
