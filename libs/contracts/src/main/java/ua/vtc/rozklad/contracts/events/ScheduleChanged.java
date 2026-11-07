package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code schedule.changed}: core → notification. Signals that lessons on these dates changed. */
public record ScheduleChanged(
        UUID eventId,
        Instant occurredAt,
        int version,
        List<LocalDate> dates,
        List<UUID> groupIds,
        List<UUID> teacherIds,
        ScheduleChangeReason reason) implements RozkladEvent {

    public ScheduleChanged {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(reason, "reason");
        dates = EventLists.copy(dates);
        groupIds = EventLists.copy(groupIds);
        teacherIds = EventLists.copy(teacherIds);
    }

    public ScheduleChanged(EventEnvelope envelope, List<LocalDate> dates, List<UUID> groupIds,
                           List<UUID> teacherIds, ScheduleChangeReason reason) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), dates, groupIds, teacherIds, reason);
    }
}
