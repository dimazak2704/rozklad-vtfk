package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Дисципліна: full {@code name} and unique {@code shortName} («СТП»). {@code color} is
 * {@code #RRGGBB} or {@code null} (UI derives a deterministic colour). When the full name is not
 * known, the abbreviation is kept as name and {@code needsReview} is set (DOMAIN §10).
 */
@Entity
@Table(name = "discipline")
public class Discipline extends BaseEntity {

    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @Column(name = "short_name", nullable = false, length = 64)
    private String shortName;

    @Column(name = "color", length = 7)
    private String color;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    protected Discipline() {
    }

    public Discipline(String name, String shortName) {
        setName(name);
        setShortName(shortName);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = Objects.requireNonNull(shortName, "shortName");
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public void setNeedsReview(boolean needsReview) {
        this.needsReview = needsReview;
    }
}
