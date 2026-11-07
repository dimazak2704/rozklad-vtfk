package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.AbsenceSource;

/**
 * {@code absence.reported}: core → notification. {@code pairs == null} means the whole day.
 * The reason category and comment are private and intentionally not part of the event.
 */
public record AbsenceReported(
        UUID eventId,
        Instant occurredAt,
        int version,
        UUID absenceId,
        UUID teacherId,
        String teacherName,
        LocalDate dateFrom,
        LocalDate dateTo,
        List<Integer> pairs,
        AbsenceSource source) implements RozkladEvent {

    public AbsenceReported {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(absenceId, "absenceId");
        Objects.requireNonNull(teacherId, "teacherId");
        Objects.requireNonNull(dateFrom, "dateFrom");
        Objects.requireNonNull(dateTo, "dateTo");
        Objects.requireNonNull(source, "source");
        pairs = EventLists.copyNullable(pairs);
    }

    public AbsenceReported(EventEnvelope envelope, UUID absenceId, UUID teacherId, String teacherName,
                           LocalDate dateFrom, LocalDate dateTo, List<Integer> pairs, AbsenceSource source) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), absenceId, teacherId, teacherName,
                dateFrom, dateTo, pairs, source);
    }
}
