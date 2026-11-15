package ua.vtc.rozklad.core.calendar.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.CalendarFixtures;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.PeriodEffect;
import ua.vtc.rozklad.core.calendar.PeriodKind;
import ua.vtc.rozklad.core.calendar.SemesterCalendar;
import ua.vtc.rozklad.core.calendar.StudyMode;

/** Study days, group-scoped blocks and study modes of DOMAIN §2, decided by the effect of the period's kind. */
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
    void studyModeKeepsLessons() {
        CalendarPeriod online = period(CalendarPeriodKind.STUDY_MODE, MONDAY, MONDAY);
        online.setStudyMode(StudyMode.ONLINE);

        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(online), GROUP_4KI1, 4)).isTrue();
        assertThat(PeriodRules.studyMode(MONDAY, List.of(online), GROUP_4KI1, 4)).isEqualTo(StudyMode.ONLINE);
    }

    /** Owner's decision of 15.11.2026: no regular pairs during the exam session (kind І, effect SUSPEND). */
    @Test
    void examSessionNowSuspendsTheGroupsLessonsButNotTheCollege() {
        CalendarPeriod exams = period(CalendarPeriodKind.EXAM_SESSION, MONDAY, MONDAY);

        assertThat(exams.getEffect()).isEqualTo(PeriodEffect.SUSPEND);
        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(exams), GROUP_4KI1, 4)).isFalse();
        assertThat(PeriodRules.isStudyDay(MONDAY, semester, List.of(exams))).as("коледж працює").isTrue();
    }

    /** A kind the dispatcher adds: the effect alone decides, the code means nothing to the rules. */
    @Test
    void customKindsBehaveByTheirEffect() {
        CalendarPeriod block = custom("ХХ", PeriodEffect.SUSPEND);
        block.applyToGroups(List.of(GROUP_4KI1));
        CalendarPeriod note = custom("Р", PeriodEffect.INFO);
        CalendarPeriod dayOff = custom("С", PeriodEffect.DAY_OFF);
        dayOff.setPeriod(MONDAY.plusDays(1), MONDAY.plusDays(1));

        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(block, note), GROUP_4KI1, 4)).isFalse();
        assertThat(PeriodRules.isGroupStudyDay(MONDAY, semester, List.of(block, note), GROUP_2KI3, 2))
                .as("позначка нічого не знімає").isTrue();
        assertThat(PeriodRules.isStudyDay(MONDAY, semester, List.of(block, note))).isTrue();
        assertThat(PeriodRules.isStudyDay(MONDAY.plusDays(1), semester, List.of(dayOff))).isFalse();
        assertThat(PeriodRules.isGroupStudyDay(MONDAY.plusDays(1), semester, List.of(dayOff), GROUP_2KI3, 2))
                .isFalse();
    }

    /** Only the effect counts: a SUSPEND kind with a study mode stored by mistake decides no study mode. */
    @Test
    void onlyOnlineKindsDecideTheStudyMode() {
        CalendarPeriod online = new CalendarPeriod(SEMESTER, new PeriodKind("ДН", "Дистанційне", PeriodEffect.ONLINE),
                "Дистанційне", MONDAY, MONDAY);
        online.setStudyMode(StudyMode.MIXED);

        assertThat(PeriodRules.studyMode(MONDAY, List.of(online), GROUP_4KI1, 4)).isEqualTo(StudyMode.MIXED);
        assertThat(PeriodRules.studyMode(MONDAY, List.of(custom("ХХ", PeriodEffect.SUSPEND)), GROUP_4KI1, 4))
                .isEqualTo(StudyMode.OFFLINE);
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
        return new CalendarPeriod(SEMESTER, CalendarFixtures.transientKind(kind), kind.name(), from, to);
    }

    private static CalendarPeriod custom(String code, PeriodEffect effect) {
        return new CalendarPeriod(SEMESTER, new PeriodKind(code, "Вид " + code, effect), code, MONDAY, MONDAY);
    }
}
