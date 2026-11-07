package ua.vtc.rozklad.core.timetable.internal.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;

/**
 * Study calendar of DOMAIN.md §2 over one or more semesters: which dates have lessons for a group,
 * their week parity and whether lessons are online. Pure logic, built by {@link StudyCalendarLoader}.
 */
public final class StudyCalendar {

    private static final String NOTE_SEPARATOR = "; ";

    private final List<SemesterCalendar> semesters;

    public StudyCalendar(List<SemesterCalendar> semesters) {
        this.semesters = List.copyOf(semesters);
    }

    public List<SemesterCalendar> semesters() {
        return semesters;
    }

    public Optional<SemesterCalendar> semesterOf(LocalDate date) {
        return semesters.stream().filter(semester -> semester.contains(date)).findFirst();
    }

    /**
     * Facts of a date for {@code group}, or for the whole college when {@code group} is
     * {@code null} (then only periods with scope ALL apply).
     */
    public DayInfo day(LocalDate date, GroupScope group) {
        int weekday = date.getDayOfWeek().getValue();
        Optional<SemesterCalendar> found = semesterOf(date);
        if (found.isEmpty()) {
            return new DayInfo(date, weekday, null, 0, false, false, null, null);
        }
        SemesterCalendar semester = found.get();
        List<StudyPeriod> active = semester.periods().stream()
                .filter(period -> period.covers(date) && period.appliesTo(group))
                .toList();
        boolean closed = active.stream().anyMatch(StudyPeriod::closesLessons);
        boolean online = active.stream().anyMatch(StudyPeriod::marksOnline);
        return new DayInfo(date, weekday, semester.parity(date), semester.weekNumber(date),
                weekday <= 5 && !closed, online, note(active), semester.semesterId());
    }

    /** Monday–Friday dates of {@code [from, to]} inside a semester that a global holiday does not close. */
    public List<LocalDate> studyDates(LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (day(date, null).studyDay()) {
                dates.add(date);
            }
        }
        return dates;
    }

    private static String note(List<StudyPeriod> active) {
        if (active.isEmpty()) {
            return null;
        }
        // спершу те, що скасовує заняття, далі форма навчання й сесія
        Set<String> titles = new LinkedHashSet<>();
        active.stream().filter(StudyPeriod::closesLessons).map(StudyPeriod::title).forEach(titles::add);
        active.stream().filter(StudyPeriod::marksOnline).map(StudyPeriod::title).forEach(titles::add);
        active.stream().filter(period -> period.kind() == CalendarPeriodKind.EXAM_SESSION)
                .map(StudyPeriod::title).forEach(titles::add);
        return titles.isEmpty() ? null : String.join(NOTE_SEPARATOR, titles);
    }
}
