package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Аудиторія. {@code name} is unique («304», «с.з.»); {@code capacity == null} means unknown (the
 * capacity check is skipped); {@code maxParallel > 1} lets several groups share the room
 * (sports hall).
 */
@Entity
@Table(name = "room")
public class Room extends BaseEntity {

    @Column(name = "building_id")
    private UUID buildingId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private RoomType type = RoomType.CLASSROOM;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "max_parallel", nullable = false)
    private int maxParallel = 1;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Room() {
    }

    public Room(String name, RoomType type) {
        setName(name);
        setType(type);
    }

    public UUID getBuildingId() {
        return buildingId;
    }

    public void setBuildingId(UUID buildingId) {
        this.buildingId = buildingId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public RoomType getType() {
        return type;
    }

    public void setType(RoomType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        if (maxParallel < 1) {
            throw new IllegalArgumentException("maxParallel must be >= 1");
        }
        this.maxParallel = maxParallel;
    }

    /** Lessons in an exclusive room take part in the room uniqueness index of {@code lesson}. */
    public boolean isExclusive() {
        return maxParallel == 1;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
