package ua.vtc.rozklad.core.catalog;

import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * Read model of a room for other modules: {@code capacity == null} means unknown (skip the
 * capacity check), {@code maxParallel > 1} lets several groups share it (sports hall).
 */
public record RoomInfo(
        UUID id,
        String name,
        RoomType type,
        Integer capacity,
        int maxParallel,
        UUID buildingId,
        boolean active) {

    public static RoomInfo of(Room room) {
        return new RoomInfo(room.getId(), room.getName(), room.getType(), room.getCapacity(),
                room.getMaxParallel(), room.getBuildingId(), room.isActive());
    }

    public RoomRef ref() {
        return new RoomRef(id, name, type);
    }

    public boolean isExclusive() {
        return maxParallel == 1;
    }
}
