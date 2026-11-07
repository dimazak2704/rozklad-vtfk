package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/** Спеціальність: {@code letters} are the letters of group codes («КІ» in «4КІ1»), unique. */
@Entity
@Table(name = "specialty")
public class Specialty extends BaseEntity {

    @Column(name = "code", length = 10)
    private String code;

    @Column(name = "letters", nullable = false, length = 10)
    private String letters;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "department_id")
    private UUID departmentId;

    protected Specialty() {
    }

    public Specialty(String letters, String name) {
        setLetters(letters);
        setName(name);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLetters() {
        return letters;
    }

    public void setLetters(String letters) {
        this.letters = Objects.requireNonNull(letters, "letters");
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }
}
