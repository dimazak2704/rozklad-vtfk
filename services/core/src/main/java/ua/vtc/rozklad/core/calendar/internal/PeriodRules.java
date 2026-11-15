package ua.vtc.rozklad.core.calendar.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.PeriodEffect;
import ua.vtc.rozklad.core.calendar.PeriodScope;
import ua.vtc.rozklad.core.calendar.SemesterCalendar;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.calendar.StudyPeriod;

/**
 * Study-day rules of DOMAIN §2, by the effect of the period's kind only. A date has lessons if it is Mon–Fri,
 * inside the semester and not inside a DAY_OFF period for everybody. For a group, DAY_OFF and SUSPEND periods
 * whose scope includes the group also remove lessons. ONLINE keeps lessons and only marks them online/mixed;
 * INFO is a note. The kind must be loaded with the periods.
 */
final class PeriodRules {

    private PeriodRules() {
    }

    static boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    static boolean appliesTo(CalendarPeriod period, UUID groupId, int course) {
        return switch (period.getScope()) {
            case ALL -> true;
            case COURSES -> period.getCourses().contains(course);
            case GROUPS -> period.getGroupIds().contains(groupId);
        };
    }

    static boolean isStudyDay(LocalDate date, SemesterCalendar semester, Collection<CalendarPeriod> periods) {
        return isWeekday(date) && semester.contains(date) && periods.stream()
                .noneMatch(period -> period.covers(date) && period.getScope() == PeriodScope.ALL
                        && period.getEffect() == PeriodEffect.DAY_OFF);
    }

    static boolean isGroupStudyDay(LocalDate date, SemesterCalendar semester, Collection<CalendarPeriod> periods,
                                   UUID groupId, int course) {
        return isWeekday(date) && semester.contains(date) && periods.stream()
                .noneMatch(period -> period.covers(date) && period.getEffect().closesLessons()
                        && appliesTo(period, groupId, course));
    }

    /**
     * The most specific ONLINE period wins (groups over courses over everybody); between
     * equally specific ones the later started decision wins. Same rule as {@link ua.vtc.rozklad.core.calendar.StudyCalendar#day}.
     */
    static StudyMode studyMode(LocalDate date, Collection<CalendarPeriod> periods, UUID groupId, int course) {
        List<StudyPeriod> active = periods.stream()
                .filter(period -> period.getEffect() == PeriodEffect.ONLINE && period.covers(date)
                        && appliesTo(period, groupId, course))
                .map(StudyPeriod::of)
                .toList();
        return StudyPeriod.decidingStudyMode(active).map(StudyPeriod::studyMode).orElse(StudyMode.OFFLINE);
    }
}
