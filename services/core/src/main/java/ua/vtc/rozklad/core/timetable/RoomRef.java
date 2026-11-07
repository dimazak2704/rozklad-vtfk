package ua.vtc.rozklad.core.timetable;

import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;

/** {@code RoomRef} of CONTRACTS.md. */
public record RoomRef(UUID id, String name, RoomType type) {
}
