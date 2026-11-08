package ua.vtc.rozklad.core.timetable.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import ua.vtc.rozklad.core.calendar.SemesterCalendar;
import ua.vtc.rozklad.core.timetable.ScheduleQueries;

/**
 * {@code from}/{@code to} of a schedule request: without them the current week (Monday to Sunday);
 * {@code to < from} or more than {@link ScheduleQueries#MAX_DAYS} days → 400 on {@code to}.
 */
record ScheduleRange(LocalDate from, LocalDate to) {

    static ScheduleRange resolve(LocalDate from, LocalDate to, Clock clock) {
        LocalDate start = from != null ? from : SemesterCalendar.weekStartOf(LocalDate.now(clock));
        LocalDate end = to != null ? to : start.plusDays(6);
        if (end.isBefore(start)) {
            throw new InvalidParameterException("to", "Кінцева дата не може бути раніше початкової");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > ScheduleQueries.MAX_DAYS) {
            throw new InvalidParameterException("to",
                    "Можна запитати не більше " + ScheduleQueries.MAX_DAYS + " днів за раз");
        }
        return new ScheduleRange(start, end);
    }
}
