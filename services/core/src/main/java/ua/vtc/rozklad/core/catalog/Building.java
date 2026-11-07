package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/** Навчальний корпус. */
@Entity
@Table(name = "building")
public class Building extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    protected Building() {
    }

    public Building(String name) {
        setName(name);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }
}
