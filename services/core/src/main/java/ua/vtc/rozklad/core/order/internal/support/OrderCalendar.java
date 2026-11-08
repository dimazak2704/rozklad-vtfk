package ua.vtc.rozklad.core.order.internal.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;

/** The order module's view of the calendar ({@link CalendarQueries}) with its own 422 messages. */
@Component
public class OrderCalendar {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CalendarQueries calendar;

    public OrderCalendar(CalendarQueries calendar) {
        this.calendar = calendar;
    }

    public Optional<SemesterInfo> findSemester(LocalDate date) {
        return calendar.semesterOn(date);
    }

    /** Orders are issued for working days inside a semester (422 otherwise). */
    public SemesterInfo requireOrderDate(LocalDate date) {
        if (date.getDayOfWeek().getValue() > DayOfWeek.FRIDAY.getValue()) {
            throw new BusinessRuleException("NOT_A_STUDY_DAY",
                    "%s — вихідний день: розпорядження складають на робочі дні.".formatted(date.format(DATE)));
        }
        return calendar.semesterOn(date).orElseThrow(() -> new BusinessRuleException("DATE_OUTSIDE_SEMESTER",
                "Дата %s не входить у жоден семестр.".formatted(date.format(DATE))));
    }

    /** NUMERATOR or DENOMINATOR of the date's week, {@code null} outside every semester. */
    public Parity parityOrNull(LocalDate date) {
        return calendar.week(date).map(week -> week.parity()).orElse(null);
    }

    /** NUMERATOR or DENOMINATOR of the date's week (a manual override of the week wins). */
    public Parity parity(LocalDate date) {
        return calendar.parity(date);
    }

    /** Notes of the calendar periods active on the date («2КІ3 – Електрорадіомонтажна практика …»). */
    public List<String> orderNotes(LocalDate date) {
        return calendar.orderNotes(date).stream().map(String::strip).distinct().toList();
    }
}
