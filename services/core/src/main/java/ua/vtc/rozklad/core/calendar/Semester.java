package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Семестр. Week 1 is the Monday-based week containing {@code startsOn}; odd weeks have
 * {@code firstWeekParity} (NUMERATOR or DENOMINATOR), {@code week_parity_override} wins
 * (DOMAIN §2). At most one semester is {@code active} (partial unique index).
 */
@Entity
@Table(name = "semester")
public class Semester extends BaseEntity {

    @Column(name = "academic_year_id", nullable = false)
    private UUID academicYearId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "first_week_parity", nullable = false, length = 16)
    private Parity firstWeekParity = Parity.NUMERATOR;

    @Column(name = "weeks_count")
    private Integer weeksCount;

    @Column(name = "bell_profile_id")
    private UUID bellProfileId;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Semester() {
    }

    public Semester(UUID academicYearId, int number, String name, LocalDate startsOn, LocalDate endsOn) {
        this.academicYearId = Objects.requireNonNull(academicYearId, "academicYearId");
        setNumber(number);
        setName(name);
        setPeriod(startsOn, endsOn);
    }

    public UUID getAcademicYearId() {
        return academicYearId;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        if (number != 1 && number != 2) {
            throw new IllegalArgumentException("semester number must be 1 or 2");
        }
        this.number = number;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public void setPeriod(LocalDate startsOn, LocalDate endsOn) {
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(endsOn, "endsOn");
        if (!endsOn.isAfter(startsOn)) {
            throw new IllegalArgumentException("endsOn must be after startsOn");
        }
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    public Parity getFirstWeekParity() {
        return firstWeekParity;
    }

    public void setFirstWeekParity(Parity firstWeekParity) {
        if (firstWeekParity == null || firstWeekParity == Parity.BOTH) {
            throw new IllegalArgumentException("first week parity must be NUMERATOR or DENOMINATOR");
        }
        this.firstWeekParity = firstWeekParity;
    }

    public Integer getWeeksCount() {
        return weeksCount;
    }

    public void setWeeksCount(Integer weeksCount) {
        this.weeksCount = weeksCount;
    }

    public UUID getBellProfileId() {
        return bellProfileId;
    }

    public void setBellProfileId(UUID bellProfileId) {
        this.bellProfileId = bellProfileId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
