package ua.vtc.rozklad.core.calendar.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.PeriodScope;
import ua.vtc.rozklad.core.calendar.SemesterCalendar;
import ua.vtc.rozklad.core.calendar.StudyMode;

/**
 * Study-day rules of DOMAIN §2. A date has lessons if it is Mon–Fri, inside the semester and not
 * inside a HOLIDAY / NO_CLASSES period for everybody. For a group, HOLIDAY, NO_CLASSES and
 * PRACTICE periods whose scope includes the group also remove lessons. STUDY_MODE keeps lessons
 * and only marks them online/mixed; EXAM_SESSION is informational.
 */
final class PeriodRules {

    private static final Set<CalendarPeriodKind> CANCEL_FOR_EVERYBODY =
            EnumSet.of(CalendarPeriodKind.HOLIDAY, CalendarPeriodKind.NO_CLASSES);
    private static final Set<CalendarPeriodKind> CANCEL_FOR_GROUP =
            EnumSet.of(CalendarPeriodKind.HOLIDAY, CalendarPeriodKind.NO_CLASSES, CalendarPeriodKind.PRACTICE);

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
                        && CANCEL_FOR_EVERYBODY.contains(period.getKind()));
    }

    static boolean isGroupStudyDay(LocalDate date, SemesterCalendar semester, Collection<CalendarPeriod> periods,
                                   UUID groupId, int course) {
        return isWeekday(date) && semester.contains(date) && periods.stream()
                .noneMatch(period -> period.covers(date) && CANCEL_FOR_GROUP.contains(period.getKind())
                        && appliesTo(period, groupId, course));
    }

    /**
     * The most specific STUDY_MODE period wins (groups over courses over everybody); between
     * equally specific ones the later started decision wins.
     */
    static StudyMode studyMode(LocalDate date, Collection<CalendarPeriod> periods, UUID groupId, int course) {
        return periods.stream()
                .filter(period -> period.getKind() == CalendarPeriodKind.STUDY_MODE && period.covers(date)
                        && appliesTo(period, groupId, course))
                .max(Comparator.comparingInt((CalendarPeriod period) -> specificity(period.getScope()))
                        .thenComparing(CalendarPeriod::getStartsOn)
                        .thenComparing(CalendarPeriod::getId))
                .map(CalendarPeriod::getStudyMode)
                .orElse(StudyMode.OFFLINE);
    }

    private static int specificity(PeriodScope scope) {
        return switch (scope) {
            case ALL -> 1;
            case COURSES -> 2;
            case GROUPS -> 3;
        };
    }
}
