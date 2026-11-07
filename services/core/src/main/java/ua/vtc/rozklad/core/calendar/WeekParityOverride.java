package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Manual parity of one week ({@code weekStart} is a Monday); wins over the computed parity. */
@Entity
@Table(name = "week_parity_override")
public class WeekParityOverride extends IdentifiableEntity {

    @Column(name = "semester_id", nullable = false)
    private UUID semesterId;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "parity", nullable = false, length = 16)
    private Parity parity;

    protected WeekParityOverride() {
    }

    public WeekParityOverride(UUID semesterId, LocalDate weekStart, Parity parity) {
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStart must be a Monday");
        }
        this.weekStart = weekStart;
        setParity(parity);
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public Parity getParity() {
        return parity;
    }

    public void setParity(Parity parity) {
        if (parity == null || parity == Parity.BOTH) {
            throw new IllegalArgumentException("override parity must be NUMERATOR or DENOMINATOR");
        }
        this.parity = parity;
    }
}
