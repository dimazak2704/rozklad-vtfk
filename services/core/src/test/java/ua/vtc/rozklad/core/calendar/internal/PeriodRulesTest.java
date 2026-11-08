package ua.vtc.rozklad.core.calendar.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.SemesterCalendar;
import ua.vtc.rozklad.core.calendar.StudyMode;

/** Study days, group-scoped practice and study modes of DOMAIN §2. */
class PeriodRulesTest {

    private static final UUID SEMESTER = UUID.randomUUID();
    private static final UUID GROUP_2KI3 = UUID.randomUUID();
    private static final UUID GROUP_4KI1 = UUID.randomUUID();
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    private final SemesterCalendar semester = new SemesterCalendar(LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 12, 30), Parity.NUMERATOR, Map.of());

    @Test
    void weekdaysInsideTheSemesterAreStudyDays() {
        assertThat(PeriodRules.isStudyDay(MONDAY, semester, List.of())).isTrue();
        assertThat(PeriodRules.isStudyDay(LocalDate.of(2026, 10, 24), semester, List.of())).as("субота").isFalse();
        assertThat(PeriodRules.isStudyDay(LocalDate.of(2026, 8, 31), semester, List.of())).as("до семестру").isFalse();
        assertThat(PeriodRules.isStudyDay(LocalDate.of(2026, 12, 31), semester, List.of())).as("після").isFalse();
    }

    @Test
    void holidayForEverybodyRemovesTheDay() {
        CalendarPeriod holiday = period(CalendarPeriodKind.HOLIDAY, MONDAY, MONDAY);

        assertThat(PeriodRules.isStudyDay(MONDAY, semester, List.of(holiday))).isFalse();
        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(holiday), GROUP_4KI1, 4)).isFalse();
        assertThat(PeriodRules.isStudyDay(MONDAY.plusDays(1), semester, List.of(holiday))).isTrue();
    }

    @Test
    void groupPracticeRemovesLessonsOfThatGroupOnly() {
        CalendarPeriod practice = period(CalendarPeriodKind.PRACTICE, MONDAY, LocalDate.of(2026, 10, 30));
        practice.applyToGroups(List.of(GROUP_2KI3));

        assertThat(PeriodRules.isStudyDay(MONDAY, semester, List.of(practice))).as("коледж навчається").isTrue();
        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(practice), GROUP_2KI3, 2)).isFalse();
        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(practice), GROUP_4KI1, 4)).isTrue();
    }

    @Test
    void courseScopedHolidayAffectsOnlyThatCourse() {
        CalendarPeriod noClasses = period(CalendarPeriodKind.NO_CLASSES, MONDAY, MONDAY);
        noClasses.applyToCourses(List.of(4));

        assertThat(PeriodRules.isStudyDay(MONDAY, semester, List.of(noClasses))).isTrue();
        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(noClasses), GROUP_4KI1, 4)).isFalse();
        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(noClasses), GROUP_2KI3, 2)).isTrue();
    }

    @Test
    void studyModeAndExamSessionKeepLessons() {
        CalendarPeriod online = period(CalendarPeriodKind.STUDY_MODE, MONDAY, MONDAY);
        online.setStudyMode(StudyMode.ONLINE);
        CalendarPeriod exams = period(CalendarPeriodKind.EXAM_SESSION, MONDAY, MONDAY);

        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(online, exams), GROUP_4KI1, 4)).isTrue();
    }

    @Test
    void mostSpecificStudyModeWins() {
        CalendarPeriod everybodyMixed = period(CalendarPeriodKind.STUDY_MODE, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 30));
        everybodyMixed.setStudyMode(StudyMode.MIXED);
        CalendarPeriod fourthCourseOnline = period(CalendarPeriodKind.STUDY_MODE, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 31));
        fourthCourseOnline.setStudyMode(StudyMode.ONLINE);
        fourthCourseOnline.applyToCourses(List.of(4));
        CalendarPeriod groupOffline = period(CalendarPeriodKind.STUDY_MODE, MONDAY, MONDAY);
        groupOffline.setStudyMode(StudyMode.OFFLINE);
        groupOffline.applyToGroups(List.of(GROUP_4KI1));
        List<CalendarPeriod> periods = List.of(everybodyMixed, fourthCourseOnline, groupOffline);

        assertThat(PeriodRules.studyMode(MONDAY, periods, GROUP_4KI1, 4)).isEqualTo(StudyMode.OFFLINE);
        assertThat(PeriodRules.studyMode(MONDAY.plusDays(1), periods, GROUP_4KI1, 4)).isEqualTo(StudyMode.ONLINE);
        assertThat(PeriodRules.studyMode(MONDAY, periods, GROUP_2KI3, 2)).isEqualTo(StudyMode.MIXED);
        assertThat(PeriodRules.studyMode(LocalDate.of(2027, 1, 20), periods, GROUP_2KI3, 2))
                .as("поза періодами — очно").isEqualTo(StudyMode.OFFLINE);
    }

    private static CalendarPeriod period(CalendarPeriodKind kind, LocalDate from, LocalDate to) {
        return new CalendarPeriod(SEMESTER, kind, kind.name(), from, to);
    }
}
