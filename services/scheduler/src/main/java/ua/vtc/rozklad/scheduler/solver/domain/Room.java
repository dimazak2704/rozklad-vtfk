package ua.vtc.rozklad.scheduler.solver.domain;

import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * Room (problem fact and planning value). {@code capacity} 0 means unknown; {@code maxParallel}
 * lessons may share the room at once (the sports hall hosts several groups). Equality is by id.
 */
public final class Room {

    /**
     * A pinned lesson that the dispatcher fixed without a room: it occupies no room and is never
     * offered to unpinned lessons.
     */
    public static final Room NONE = new Room(new UUID(0, 0), "—", null, 0, Integer.MAX_VALUE);

    private final UUID id;
    private final String name;
    private final RoomType type;
    private final int capacity;
    private final int maxParallel;

    public Room(UUID id, String name, RoomType type, int capacity, int maxParallel) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
        this.type = type;
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        if (maxParallel < 1) {
            throw new IllegalArgumentException("maxParallel must be positive: " + maxParallel);
        }
        this.capacity = capacity;
        this.maxParallel = maxParallel;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public RoomType getType() {
        return type;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public boolean isNone() {
        return this == NONE;
    }

    public boolean hasKnownCapacity() {
        return capacity > 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Room room && id.equals(room.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
