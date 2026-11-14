package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Study calendar of DOMAIN.md §2 over one or more semesters: which dates have lessons for a group,
 * their week parity and whether lessons are online. Pure logic; get it from {@link CalendarQueries#studyCalendar}.
 */
public final class StudyCalendar {

    private static final String NOTE_SEPARATOR = "; ";
    private static final Comparator<StudyPeriod> BY_START =
            Comparator.comparing(StudyPeriod::startsOn).thenComparing(StudyPeriod::title);

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
        // періоди форми навчання не додаються: вирішує найконкретніший (група > курс > усі)
        Optional<StudyPeriod> studyMode = StudyPeriod.decidingStudyMode(active).filter(StudyPeriod::marksOnline);
        return new DayInfo(date, weekday, semester.parity(date), semester.weekNumber(date),
                weekday <= 5 && !closed, studyMode.isPresent(), note(active, studyMode), semester.semesterId());
    }

    /**
     * PRACTICE periods of {@code group} on a Monday–Friday date of a semester. A holiday or a day without
     * classes that applies to the group wins: nobody is at practice then, so the list is empty.
     */
    public List<StudyPeriod> practices(LocalDate date, GroupScope group) {
        if (group == null) {
            return List.of();
        }
        List<StudyPeriod> active = activePeriods(date, group);
        return active.stream().anyMatch(StudyCalendar::isDayOff) ? List.of() : active.stream()
                .filter(period -> period.kind() == CalendarPeriodKind.PRACTICE)
                .sorted(BY_START)
                .toList();
    }

    /**
     * PRACTICE periods {@code teacherId} supervises on a Monday–Friday date of a semester that no
     * college-wide holiday or day without classes closes.
     */
    public List<StudyPeriod> supervisedPractices(LocalDate date, UUID teacherId) {
        if (teacherId == null || activePeriods(date, null).stream().anyMatch(StudyCalendar::isDayOff)) {
            return List.of();
        }
        return semesterOf(date).filter(semester -> date.getDayOfWeek().getValue() <= 5).stream()
                .flatMap(semester -> semester.periods().stream())
                .filter(period -> period.kind() == CalendarPeriodKind.PRACTICE && period.covers(date)
                        && period.supervisorIds().contains(teacherId))
                .sorted(BY_START)
                .toList();
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

    private static String note(List<StudyPeriod> active, Optional<StudyPeriod> online) {
        if (active.isEmpty()) {
            return null;
        }
        // спершу те, що скасовує заняття, далі форма навчання й сесія
        Set<String> titles = new LinkedHashSet<>();
        active.stream().filter(StudyPeriod::closesLessons).map(StudyPeriod::title).forEach(titles::add);
        online.map(StudyPeriod::title).ifPresent(titles::add);
        active.stream().filter(period -> period.kind() == CalendarPeriodKind.EXAM_SESSION)
                .map(StudyPeriod::title).forEach(titles::add);
        return titles.isEmpty() ? null : String.join(NOTE_SEPARATOR, titles);
    }

    /** Periods covering a Monday–Friday date of a semester that apply to {@code group}; empty otherwise. */
    private List<StudyPeriod> activePeriods(LocalDate date, GroupScope group) {
        if (date.getDayOfWeek().getValue() > 5) {
            return List.of();
        }
        return semesterOf(date).stream()
                .flatMap(semester -> semester.periods().stream())
                .filter(period -> period.covers(date) && period.appliesTo(group))
                .toList();
    }

    private static boolean isDayOff(StudyPeriod period) {
        return period.kind() == CalendarPeriodKind.HOLIDAY || period.kind() == CalendarPeriodKind.NO_CLASSES;
    }
}
