package ua.vtc.rozklad.core.catalog.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.shared.web.Ref;

/** DTOs of rooms. */
public final class RoomDtos {

    public static final int MAX_CAPACITY = 1000;
    public static final int MAX_PARALLEL = 20;

    private RoomDtos() {
    }

    public record RoomDto(UUID id, Ref building, String name, RoomType type, Integer capacity, int maxParallel,
                          boolean active, long version) {

        static RoomDto of(Room room, Ref building) {
            return new RoomDto(room.getId(), building, room.getName(), room.getType(), room.getCapacity(),
                    room.getMaxParallel(), room.isActive(), room.getVersion());
        }
    }

    /**
     * @param capacity    {@code null} = unknown (the capacity check is skipped)
     * @param maxParallel groups at the same time, 1 when empty (sports hall «с.з.» hosts several)
     * @param active      {@code true} when empty
     */
    public record RoomRequest(
            UUID buildingId,
            @NotBlank @Size(max = 50) String name,
            @NotNull RoomType type,
            @Min(0) @Max(MAX_CAPACITY) Integer capacity,
            @Min(1) @Max(MAX_PARALLEL) Integer maxParallel,
            Boolean active,
            Long version) {
    }
}
