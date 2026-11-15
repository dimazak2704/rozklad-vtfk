package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Study calendar of DOMAIN.md §2 over one or more semesters: which dates have lessons for a group,
 * their week parity and whether lessons are online. Behaviour comes from the effects of the periods' kinds
 * (priority DAY_OFF > SUSPEND > ONLINE > INFO). Pure logic; get it from {@link CalendarQueries#studyCalendar}.
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
     * Blocks of the day of {@code group}: periods of a SUSPEND kind (practice, НС, сесія…) on a Monday–Friday
     * date of a semester. A DAY_OFF period that applies to the group wins: nobody is at a block then, so the
     * list is empty.
     */
    public List<StudyPeriod> practices(LocalDate date, GroupScope group) {
        if (group == null) {
            return List.of();
        }
        List<StudyPeriod> active = activePeriods(date, group);
        return active.stream().anyMatch(StudyPeriod::isDayOff) ? List.of() : active.stream()
                .filter(StudyPeriod::suspends)
                .sorted(BY_START)
                .toList();
    }

    /**
     * Periods of a SUSPEND kind {@code teacherId} supervises on a Monday–Friday date of a semester that no
     * college-wide DAY_OFF period closes.
     */
    public List<StudyPeriod> supervisedPractices(LocalDate date, UUID teacherId) {
        if (teacherId == null || activePeriods(date, null).stream().anyMatch(StudyPeriod::isDayOff)) {
            return List.of();
        }
        return semesterOf(date).filter(semester -> date.getDayOfWeek().getValue() <= 5).stream()
                .flatMap(semester -> semester.periods().stream())
                .filter(period -> period.suspends() && period.covers(date)
                        && period.supervisorIds().contains(teacherId))
                .sorted(BY_START)
                .toList();
    }

    /**
     * The days {@code group} is on theory in the semester (owner's rule 15.11.2026): study days of the group per
     * weekday and parity, the weeks that have any, and the periods that take the group off its pairs. One pass over
     * the semester's dates, no queries.
     *
     * @throws IllegalArgumentException when the semester is not part of this calendar
     */
    public GroupTheory theory(UUID semesterId, GroupScope group) {
        Objects.requireNonNull(group, "group");
        StudySemester semester = semesters.stream().filter(candidate -> candidate.semesterId().equals(semesterId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("semester " + semesterId + " is not in the calendar"));
        List<StudyPeriod> closing = semester.periods().stream()
                .filter(period -> period.closesLessons() && period.appliesTo(group))
                .toList();
        GroupTheory.StudyDays.Builder days = GroupTheory.StudyDays.builder();
        Set<LocalDate> numeratorWeeks = new HashSet<>();
        Set<LocalDate> denominatorWeeks = new HashSet<>();
        Set<StudyPeriod> taking = new LinkedHashSet<>();
        int studyDays = 0;
        for (LocalDate date = semester.startsOn(); !date.isAfter(semester.endsOn()); date = date.plusDays(1)) {
            int weekday = date.getDayOfWeek().getValue();
            if (weekday > 5) {
                continue;
            }
            LocalDate day = date;
            List<StudyPeriod> closedBy = closing.stream().filter(period -> period.covers(day)).toList();
            if (!closedBy.isEmpty()) {
                taking.addAll(closedBy);
                continue;
            }
            Parity parity = semester.parity(date);
            days.add(weekday, parity);
            (parity == Parity.NUMERATOR ? numeratorWeeks : denominatorWeeks).add(SemesterCalendar.weekStartOf(date));
            studyDays++;
        }
        return new GroupTheory(semesterId, group.groupId(), semester.weeks().calendarWeeks(),
                numeratorWeeks.size() + denominatorWeeks.size(), numeratorWeeks.size(), denominatorWeeks.size(),
                studyDays, taking.stream().sorted(BY_START).toList(), days.build());
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
        // спершу те, що скасовує заняття (вихідні й блоки), далі форма навчання, потім позначки
        Set<String> titles = new LinkedHashSet<>();
        active.stream().filter(StudyPeriod::closesLessons).map(StudyPeriod::title).forEach(titles::add);
        online.map(StudyPeriod::title).ifPresent(titles::add);
        active.stream().filter(period -> period.effect() == PeriodEffect.INFO)
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
}
