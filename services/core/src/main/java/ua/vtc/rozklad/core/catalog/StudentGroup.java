package ua.vtc.rozklad.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Група: canonical {@code code} «4КІ1» = course 4 + specialty letters + number (number may be
 * absent, e.g. «4ВЕ»). {@code shift} 1 or 2 selects the shift window; {@code size == null} means
 * unknown.
 */
@Entity
@Table(name = "student_group")
public class StudentGroup extends BaseEntity {

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "course", nullable = false)
    private int course;

    @Column(name = "specialty_id", nullable = false)
    private UUID specialtyId;

    @Column(name = "number")
    private Integer number;

    @Column(name = "size")
    private Integer size;

    @Column(name = "shift", nullable = false)
    private int shift = 1;

    @Column(name = "curator_teacher_id")
    private UUID curatorTeacherId;

    @Column(name = "home_room_id")
    private UUID homeRoomId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected StudentGroup() {
    }

    public StudentGroup(String code, int course, UUID specialtyId) {
        setCode(code);
        setCourse(course);
        setSpecialtyId(specialtyId);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = Objects.requireNonNull(code, "code");
    }

    public int getCourse() {
        return course;
    }

    public void setCourse(int course) {
        this.course = course;
    }

    public UUID getSpecialtyId() {
        return specialtyId;
    }

    public void setSpecialtyId(UUID specialtyId) {
        this.specialtyId = Objects.requireNonNull(specialtyId, "specialtyId");
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }

    public int getShift() {
        return shift;
    }

    public void setShift(int shift) {
        if (shift != 1 && shift != 2) {
            throw new IllegalArgumentException("shift must be 1 or 2");
        }
        this.shift = shift;
    }

    public UUID getCuratorTeacherId() {
        return curatorTeacherId;
    }

    public void setCuratorTeacherId(UUID curatorTeacherId) {
        this.curatorTeacherId = curatorTeacherId;
    }

    public UUID getHomeRoomId() {
        return homeRoomId;
    }

    public void setHomeRoomId(UUID homeRoomId) {
        this.homeRoomId = homeRoomId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
