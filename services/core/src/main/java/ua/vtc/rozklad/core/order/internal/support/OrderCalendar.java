package ua.vtc.rozklad.core.order.internal.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.WeekParityOverride;
import ua.vtc.rozklad.core.calendar.WeekParityOverrideRepository;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.time.StudyWeeks;

/** Calendar facts of the order module: semester of a date, week parity (DOMAIN §2), order notes. */
@Component
public class OrderCalendar {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SemesterRepository semesters;
    private final WeekParityOverrideRepository parityOverrides;
    private final CalendarPeriodRepository periods;

    public OrderCalendar(SemesterRepository semesters, WeekParityOverrideRepository parityOverrides,
                         CalendarPeriodRepository periods) {
        this.semesters = semesters;
        this.parityOverrides = parityOverrides;
        this.periods = periods;
    }

    public Optional<Semester> findSemester(LocalDate date) {
        return semesters.findContaining(date);
    }

    /** Orders are issued for working days inside a semester (422 otherwise). */
    public Semester requireOrderDate(LocalDate date) {
        if (!StudyWeeks.isWeekday(date)) {
            throw new BusinessRuleException("NOT_A_STUDY_DAY",
                    "%s — вихідний день: розпорядження складають на робочі дні.".formatted(date.format(DATE)));
        }
        return semesters.findContaining(date).orElseThrow(() -> new BusinessRuleException("DATE_OUTSIDE_SEMESTER",
                "Дата %s не входить у жоден семестр.".formatted(date.format(DATE))));
    }

    /** NUMERATOR or DENOMINATOR of the date's week; a manual override of the week wins. */
    public Parity parity(Semester semester, LocalDate date) {
        Parity override = parityOverrides.findBySemesterIdAndWeekStart(semester.getId(), StudyWeeks.weekStart(date))
                .map(WeekParityOverride::getParity)
                .orElse(null);
        return StudyWeeks.parity(semester.getStartsOn(), semester.getFirstWeekParity(), date, override);
    }

    /** Notes of the calendar periods active on the date («2КІ3 – Електрорадіомонтажна практика …»). */
    public List<String> orderNotes(Semester semester, LocalDate date) {
        return periods.findOverlapping(semester.getId(), date, date).stream()
                .filter((CalendarPeriod period) -> period.covers(date))
                .map(CalendarPeriod::getOrderNote)
                .filter(note -> note != null && !note.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }
}
