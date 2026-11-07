package ua.vtc.rozklad.core.load;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.IdentifiableEntity;

/** Teacher of one subgroup of a load item (unique per load item and subgroup). */
@Entity
@Table(name = "load_item_teacher")
public class LoadItemTeacher extends IdentifiableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "load_item_id", nullable = false, updatable = false)
    private LoadItem loadItem;

    @Column(name = "subgroup_number", nullable = false)
    private int subgroupNumber;

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    protected LoadItemTeacher() {
    }

    LoadItemTeacher(LoadItem loadItem, int subgroupNumber, UUID teacherId) {
        if (subgroupNumber < 0) {
            throw new IllegalArgumentException("subgroup number must be >= 0");
        }
        this.loadItem = Objects.requireNonNull(loadItem, "loadItem");
        this.subgroupNumber = subgroupNumber;
        this.teacherId = Objects.requireNonNull(teacherId, "teacherId");
    }

    public LoadItem getLoadItem() {
        return loadItem;
    }

    public int getSubgroupNumber() {
        return subgroupNumber;
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    void setTeacherId(UUID teacherId) {
        this.teacherId = Objects.requireNonNull(teacherId, "teacherId");
    }
}
