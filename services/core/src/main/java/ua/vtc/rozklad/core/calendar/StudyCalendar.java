package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Study calendar of DOMAIN.md §2 over one or more semesters: which dates have lessons for a group,
 * their week parity and whether lessons are online. Pure logic; get it from {@link CalendarQueries#studyCalendar}.
 */
public final class StudyCalendar {

    private static final String NOTE_SEPARATOR = "; ";

    private final List<StudySemester> semesters;

    public StudyCalendar(List<StudySemester> semesters) {
        this.semesters = List.copyOf(semesters);
    }

    public List<StudySemester> semesters() {
        return semesters;
    }

    public Optional<StudySemester> semesterOf(LocalDate date) {
        return semesters.stream().filter(semester -> semester.contains(date)).findFirst();
    }

    /**
     * Facts of a date for {@code group}, or for the whole college when {@code group} is
     * {@code null} (then only periods with scope ALL apply).
     */
    public DayInfo day(LocalDate date, GroupScope group) {
        int weekday = date.getDayOfWeek().getValue();
        Optional<StudySemester> found = semesterOf(date);
        if (found.isEmpty()) {
            return new DayInfo(date, weekday, null, 0, false, false, null, null);
        }
        StudySemester semester = found.get();
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
