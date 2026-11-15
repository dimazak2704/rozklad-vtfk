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
import java.util.Set;
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
    void practiceIsShownToItsGroupAndItsSupervisorsOnWorkingDaysOnly() {
        UUID supervisor = UUID.randomUUID();
        StudyPeriod practice = new StudyPeriod(CalendarPeriodKind.PRACTICE, "Електрорадіомонтажна практика",
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 30), null, PeriodScope.GROUPS, Set.of(),
                Set.of(GROUP_2KI3.groupId()), UUID.randomUUID(), Set.of(supervisor));
        StudyCalendar withSupervisor = calendar(List.of(practice,
                holiday("Осінні канікули", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 30))));
        LocalDate tuesday = LocalDate.of(2026, 10, 20);

        assertThat(withSupervisor.practices(tuesday, GROUP_2KI3)).containsExactly(practice);
        assertThat(withSupervisor.practices(tuesday, GROUP_4KI1)).as("інша група").isEmpty();
        assertThat(withSupervisor.practices(tuesday, null)).isEmpty();
        assertThat(withSupervisor.supervisedPractices(tuesday, supervisor)).containsExactly(practice);
        assertThat(withSupervisor.supervisedPractices(tuesday, UUID.randomUUID())).isEmpty();
        assertThat(withSupervisor.practices(LocalDate.of(2026, 10, 24), GROUP_2KI3)).as("субота").isEmpty();
        assertThat(withSupervisor.supervisedPractices(LocalDate.of(2026, 10, 24), supervisor)).isEmpty();
        assertThat(withSupervisor.practices(LocalDate.of(2026, 10, 27), GROUP_2KI3)).as("канікули").isEmpty();
        assertThat(withSupervisor.supervisedPractices(LocalDate.of(2026, 10, 27), supervisor)).isEmpty();
        assertThat(withSupervisor.practices(LocalDate.of(2026, 11, 2), GROUP_2KI3)).as("після практики").isEmpty();
    }

    /** A kind the dispatcher adds with effect SUSPEND behaves like practice: no pairs, a block of the day. */
    @Test
    void customSuspendKindIsABlockOfTheDayForItsGroup() {
        UUID supervisor = UUID.randomUUID();
        UUID room401 = UUID.randomUUID();
        StudyPeriod block = new StudyPeriod(CalendarFixtures.customKind("ХХ", "Новий вид", PeriodEffect.SUSPEND),
                "Основи національного спротиву", LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 27), null,
                PeriodScope.GROUPS, Set.of(), Set.of(GROUP_4KI1.groupId()), UUID.randomUUID(), Set.of(supervisor),
                room401);
        StudyCalendar withBlock = calendar(List.of(block));
        LocalDate friday = LocalDate.of(2026, 11, 20);

        assertThat(withBlock.day(friday, GROUP_4KI1)).satisfies(day -> {
            assertThat(day.studyDay()).isFalse();
            assertThat(day.note()).isEqualTo("Основи національного спротиву");
        });
        assertThat(withBlock.day(friday, GROUP_2KI3).studyDay()).isTrue();
        assertThat(withBlock.practices(friday, GROUP_4KI1)).singleElement().satisfies(found -> {
            assertThat(found.kind().code()).isEqualTo("ХХ");
            assertThat(found.roomId()).isEqualTo(room401);
        });
        assertThat(withBlock.supervisedPractices(friday, supervisor)).containsExactly(block);
    }

    /** Owner's decision of 15.11.2026: the exam session (І) suspends the group's pairs and is a block of the day. */
    @Test
    void examSessionSuspendsTheGroupsLessons() {
        StudyCalendar session = calendar(List.of(new StudyPeriod(CalendarPeriodKind.EXAM_SESSION,
                "Екзаменаційна сесія 4 курсу", LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 30), null,
                PeriodScope.COURSES, Set.of(4), Set.of())));
        LocalDate date = LocalDate.of(2026, 12, 22);

        assertThat(session.day(date, GROUP_4KI1)).satisfies(day -> {
            assertThat(day.studyDay()).isFalse();
            assertThat(day.note()).isEqualTo("Екзаменаційна сесія 4 курсу");
        });
        assertThat(session.practices(date, GROUP_4KI1)).singleElement()
                .satisfies(found -> assertThat(found.kind().name()).isEqualTo("Сесія"));
        assertThat(session.day(date, GROUP_2KI3).studyDay()).isTrue();
        assertThat(session.studyDates(date, date)).as("коледж працює").containsExactly(date);
    }

    /** Priority DAY_OFF > SUSPEND > ONLINE > INFO; ONLINE is decided on its own, INFO is a note only. */
    @Test
    void dayOffWinsOverABlockAndNotesFollowThePriority() {
        GroupScope group = new GroupScope(UUID.randomUUID(), 4);
        LocalDate monday = LocalDate.of(2026, 11, 16);
        StudyCalendar overlapping = calendar(List.of(
                new StudyPeriod(CalendarFixtures.customKind("Р", "Резерв", PeriodEffect.INFO), "Резерв часу",
                        monday, monday.plusDays(4), null, PeriodScope.ALL, Set.of(), Set.of()),
                onlineForCourses("4 курс дистанційно", monday, monday.plusDays(4), 4),
                new StudyPeriod(CalendarFixtures.customKind("ХХ", "Новий вид", PeriodEffect.SUSPEND), "Блок",
                        monday, monday.plusDays(4), null, PeriodScope.GROUPS, Set.of(), Set.of(group.groupId())),
                holiday("Свято", monday.plusDays(2), monday.plusDays(2))));

        assertThat(overlapping.day(monday, group)).satisfies(day -> {
            assertThat(day.studyDay()).isFalse();
            assertThat(day.online()).as("онлайн рахується окремо від блоку").isTrue();
            assertThat(day.note()).isEqualTo("Блок; 4 курс дистанційно; Резерв часу");
        });
        assertThat(overlapping.practices(monday, group)).hasSize(1);
        assertThat(overlapping.practices(monday.plusDays(2), group)).as("вихідний перемагає блок").isEmpty();
        assertThat(overlapping.day(monday, GROUP_2KI3)).satisfies(day -> {
            assertThat(day.studyDay()).as("позначка нічого не знімає").isTrue();
            assertThat(day.note()).isEqualTo("Резерв часу");
        });
    }

    @Test
    void studyDatesSkipWeekendsAndHolidays() {
        assertThat(calendar.studyDates(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 11, 2))).containsExactly(
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21),
                LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23), LocalDate.of(2026, 11, 2));
    }
}
