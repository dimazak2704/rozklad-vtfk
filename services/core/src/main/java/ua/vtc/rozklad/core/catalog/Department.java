package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/** Відділення, e.g. «Комп'ютерне». */
@Entity
@Table(name = "department")
public class Department extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "short_name", length = 50)
    private String shortName;

    protected Department() {
    }

    public Department(String name, String shortName) {
        setName(name);
        this.shortName = shortName;
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
        this.shortName = shortName;
    }
}
