package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;

/** SSE data of {@code schedule.changed}: which dates, groups and teachers to refetch. */
public record ScheduleChangedNotice(List<LocalDate> dates, List<UUID> groupIds, List<UUID> teacherIds,
                                    ScheduleChangeReason reason) {

    public static ScheduleChangedNotice of(ScheduleChanged event) {
        return new ScheduleChangedNotice(event.dates(), event.groupIds(), event.teacherIds(), event.reason());
    }
}
