package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * In-process event of a calendar edit (period, parity override, semester dates), published inside
 * the edit's transaction after the edit is flushed and before {@code schedule.changed}. A listener
 * may refuse the edit by throwing: the whole transaction rolls back.
 *
 * @param dates    Mon–Fri dates whose lessons may change
 * @param groupIds groups whose lessons may change
 * @param before   the calendar of those dates as it was before the edit, so a listener can tell what
 *                 the edit itself changes from what was already so
 */
public record CalendarEdited(List<LocalDate> dates, List<UUID> groupIds, StudyCalendar before) {

    public CalendarEdited {
        dates = List.copyOf(dates);
        groupIds = List.copyOf(groupIds);
    }
}
