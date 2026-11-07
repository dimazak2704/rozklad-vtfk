package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * Catalog facts of a room.
 *
 * @param capacity    seats, {@code null} when unknown (capacity check skipped)
 * @param maxParallel how many groups may use the room at once (sports hall «с.з.» hosts several)
 */
public record RoomFact(UUID id, String name, RoomType type, Integer capacity, int maxParallel) {

    public RoomFact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (maxParallel < 1) {
            throw new IllegalArgumentException("maxParallel must be >= 1");
        }
    }
}
