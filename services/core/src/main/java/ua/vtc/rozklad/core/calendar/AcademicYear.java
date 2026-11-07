package ua.vtc.rozklad.core.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/** Навчальний рік, e.g. «2026/2027». */
@Entity
@Table(name = "academic_year")
public class AcademicYear extends BaseEntity {

    @Column(name = "name", nullable = false, length = 20)
    private String name;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    protected AcademicYear() {
    }

    public AcademicYear(String name, LocalDate startsOn, LocalDate endsOn) {
        setName(name);
        setPeriod(startsOn, endsOn);
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
}
