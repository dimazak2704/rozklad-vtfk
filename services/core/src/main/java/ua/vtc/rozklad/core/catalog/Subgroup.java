package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/** Підгрупа 1..n of a group; number 0 (the whole group) is implicit and never stored. */
@Entity
@Table(name = "subgroup")
public class Subgroup extends BaseEntity {

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "size")
    private Integer size;

    protected Subgroup() {
    }

    public Subgroup(UUID groupId, int number) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        setNumber(number);
    }

    public UUID getGroupId() {
        return groupId;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("subgroup number must be >= 1");
        }
        this.number = number;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }
}
