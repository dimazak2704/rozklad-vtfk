package ua.vtc.rozklad.core.substitution.internal.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.GroupScope;
import ua.vtc.rozklad.core.calendar.PairTime;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.calendar.ShiftWindows;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.StudyPeriod;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;

/**
 * The substitution module's view of the calendar: every rule (semester of a date, periods without
 * classes, study mode, bells, shift windows) comes from {@link CalendarQueries}; this class only
 * adds the module's Ukrainian 422 messages.
 */
@Component
public class SubstitutionCalendar {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CalendarQueries calendar;

    public SubstitutionCalendar(CalendarQueries calendar) {
        this.calendar = calendar;
    }

    public Optional<SemesterInfo> findSemester(LocalDate date) {
        return calendar.semesterOn(date);
    }

    /** The semester of a working day; 422 for weekends and dates outside every semester. */
    public SemesterInfo requireStudyDate(LocalDate date) {
        if (date.getDayOfWeek().getValue() > DayOfWeek.FRIDAY.getValue()) {
            throw new BusinessRuleException("NOT_A_STUDY_DAY",
                    "%s — вихідний день: заняття проводяться з понеділка по п'ятницю.".formatted(date.format(DATE)));
        }
        return calendar.semesterOn(date).orElseThrow(() -> new BusinessRuleException("DATE_OUTSIDE_SEMESTER",
                "Дата %s не входить у жоден семестр.".formatted(date.format(DATE))));
    }

    /** Calendar facts of {@code [from, to]} for batch checks (make-up slots). */
    public StudyCalendar range(LocalDate from, LocalDate to) {
        return calendar.studyCalendar(from, to);
    }

    /** Title of the holiday / no-classes / practice period that cancels the group's lessons on the date. */
    public Optional<String> noClassesReason(LocalDate date, StudentGroup group) {
        return noClassesReason(calendar.studyCalendar(date, date), date, group);
    }

    public static Optional<String> noClassesReason(StudyCalendar days, LocalDate date, StudentGroup group) {
        GroupScope scope = scope(group);
        return days.semesterOf(date).stream()
                .flatMap(semester -> semester.periods().stream())
                .filter(period -> period.covers(date) && period.closesLessons() && period.appliesTo(scope))
                .map(StudyPeriod::title)
                .findFirst();
    }

    /** {@code true} when an online or mixed study-mode period covers the group on the date. */
    public boolean onlineFor(LocalDate date, StudentGroup group) {
        return calendar.studyCalendar(date, date).day(date, scope(group)).online();
    }

    public static GroupScope scope(StudentGroup group) {
        return new GroupScope(group.getId(), group.getCourse());
    }

    /** Pair times of the date by pair number; empty when no bell profile is known or on weekends. */
    public Map<Integer, PairTime> pairTimes(LocalDate date) {
        return calendar.bellSlots(date).stream().collect(Collectors.toMap(PairTime::pair, Function.identity()));
    }

    /** Pairs a group of the shift may have on the weekday (1 = Monday); empty = not restricted. */
    public Optional<ShiftWindows.PairRange> shiftWindow(int shift, int weekday) {
        return calendar.shiftWindowTable().window(shift, weekday);
    }
}
