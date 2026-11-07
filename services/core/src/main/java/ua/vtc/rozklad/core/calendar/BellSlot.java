package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.Objects;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Time of a pair on a weekday (1 = Monday … 5 = Friday) within a bell profile. */
@Entity
@Table(name = "bell_slot")
public class BellSlot extends IdentifiableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false, updatable = false)
    private BellProfile profile;

    @Column(name = "weekday", nullable = false)
    private int weekday;

    @Column(name = "pair_number", nullable = false)
    private int pairNumber;

    @Column(name = "starts_at", nullable = false)
    private LocalTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalTime endsAt;

    protected BellSlot() {
    }

    BellSlot(BellProfile profile, int weekday, int pairNumber, LocalTime startsAt, LocalTime endsAt) {
        this.profile = Objects.requireNonNull(profile, "profile");
        if (weekday < 1 || weekday > 5 || pairNumber < 1 || pairNumber > 8) {
            throw new IllegalArgumentException("weekday must be 1..5 and pair 1..8");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        this.weekday = weekday;
        this.pairNumber = pairNumber;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public BellProfile getProfile() {
        return profile;
    }

    public int getWeekday() {
        return weekday;
    }

    public int getPairNumber() {
        return pairNumber;
    }

    public LocalTime getStartsAt() {
        return startsAt;
    }

    public LocalTime getEndsAt() {
        return endsAt;
    }
}
