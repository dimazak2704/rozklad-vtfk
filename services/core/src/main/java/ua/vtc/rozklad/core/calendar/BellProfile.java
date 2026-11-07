package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/** Профіль дзвінків («2 зміни, 60 хв») with its slots; slots are saved together with the profile. */
@Entity
@Table(name = "bell_profile")
public class BellProfile extends BaseEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("weekday ASC, pairNumber ASC")
    private List<BellSlot> slots = new ArrayList<>();

    protected BellProfile() {
    }

    public BellProfile(String name) {
        setName(name);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public List<BellSlot> getSlots() {
        return Collections.unmodifiableList(slots);
    }

    public BellSlot addSlot(int weekday, int pairNumber, LocalTime startsAt, LocalTime endsAt) {
        BellSlot slot = new BellSlot(this, weekday, pairNumber, startsAt, endsAt);
        slots.add(slot);
        return slot;
    }

    /** Replaces all slots; orphan removal deletes the old rows. */
    public void clearSlots() {
        slots.clear();
    }
}
