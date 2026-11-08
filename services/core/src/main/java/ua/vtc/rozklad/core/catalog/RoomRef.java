package ua.vtc.rozklad.core.catalog;

import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;

/** {@code RoomRef {id, name, type}} of CONTRACTS.md. */
public record RoomRef(UUID id, String name, RoomType type) {

    public RoomRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    public static RoomRef of(Room room) {
        return new RoomRef(room.getId(), room.getName(), room.getType());
    }
}
