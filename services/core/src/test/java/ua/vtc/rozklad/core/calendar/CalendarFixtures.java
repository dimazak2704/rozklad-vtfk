package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/** Semester «I семестр 2026/2027» (01.09–30.12.2026, first week numerator) with periods of DOMAIN.md §12. */
public final class CalendarFixtures {

    public static final UUID SEMESTER_ID = UUID.fromString("0190f0a0-0000-7000-8000-00000000a001");
    public static final LocalDate STARTS = LocalDate.of(2026, 9, 1);
    public static final LocalDate ENDS = LocalDate.of(2026, 12, 30);

    private CalendarFixtures() {
    }

    public static StudyCalendar calendar(List<StudyPeriod> periods) {
        return calendar(periods, Map.of());
    }

    public static StudyCalendar calendar(List<StudyPeriod> periods, Map<LocalDate, Parity> overrides) {
        return new StudyCalendar(List.of(new StudySemester(SEMESTER_ID, STARTS, ENDS,
                new SemesterCalendar(STARTS, ENDS, Parity.NUMERATOR, overrides), periods, null)));
    }

    public static StudyPeriod holiday(String title, LocalDate from, LocalDate to) {
        return new StudyPeriod(CalendarPeriodKind.HOLIDAY, title, from, to, null, PeriodScope.ALL, Set.of(), Set.of());
    }

    public static StudyPeriod practice(String title, LocalDate from, LocalDate to, UUID... groups) {
        return new StudyPeriod(CalendarPeriodKind.PRACTICE, title, from, to, null, PeriodScope.GROUPS, Set.of(),
                Set.of(groups));
    }

    public static StudyPeriod onlineForCourses(String title, LocalDate from, LocalDate to, Integer... courses) {
        return new StudyPeriod(CalendarPeriodKind.STUDY_MODE, title, from, to, StudyMode.ONLINE, PeriodScope.COURSES,
                Set.of(courses), Set.of());
    }
}
