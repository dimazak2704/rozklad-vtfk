package ua.vtc.rozklad.core.load;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Навантаження: group × discipline × semester with hours per week and planned hours, plus the
 * teacher of every subgroup (subgroup 0 when the group is not split). Weekly units are derived
 * from {@code hoursPerWeek} (DOMAIN §4).
 */
@Entity
@Table(name = "load_item")
public class LoadItem extends BaseEntity {

    @Column(name = "semester_id", nullable = false)
    private UUID semesterId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "discipline_id", nullable = false)
    private UUID disciplineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lesson_kind", nullable = false, length = 16)
    private LessonKind lessonKind = LessonKind.MIXED;

    @Column(name = "hours_per_week", nullable = false, precision = 4, scale = 1)
    private BigDecimal hoursPerWeek;

    @Column(name = "planned_hours", nullable = false)
    private int plannedHours;

    @Column(name = "split_subgroups", nullable = false)
    private boolean splitSubgroups;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_room_type", length = 20)
    private RoomType requiredRoomType;

    @Column(name = "preferred_room_id")
    private UUID preferredRoomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_form", nullable = false, length = 16)
    private ControlForm controlForm = ControlForm.NONE;

    @Column(name = "stream_id")
    private UUID streamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private LoadSource source = LoadSource.MANUAL;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @OneToMany(mappedBy = "loadItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("subgroupNumber ASC")
    private List<LoadItemTeacher> teachers = new ArrayList<>();

    protected LoadItem() {
    }

    public LoadItem(UUID semesterId, UUID groupId, UUID disciplineId, BigDecimal hoursPerWeek, int plannedHours) {
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.disciplineId = Objects.requireNonNull(disciplineId, "disciplineId");
        setHours(hoursPerWeek, plannedHours);
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public UUID getDisciplineId() {
        return disciplineId;
    }

    public void setDisciplineId(UUID disciplineId) {
        this.disciplineId = Objects.requireNonNull(disciplineId, "disciplineId");
    }

    public LessonKind getLessonKind() {
        return lessonKind;
    }

    public void setLessonKind(LessonKind lessonKind) {
        this.lessonKind = Objects.requireNonNull(lessonKind, "lessonKind");
    }

    public BigDecimal getHoursPerWeek() {
        return hoursPerWeek;
    }

    public int getPlannedHours() {
        return plannedHours;
    }

    public void setHours(BigDecimal hoursPerWeek, int plannedHours) {
        Objects.requireNonNull(hoursPerWeek, "hoursPerWeek");
        if (hoursPerWeek.signum() <= 0) {
            throw new IllegalArgumentException("hoursPerWeek must be positive");
        }
        if (plannedHours < 0) {
            throw new IllegalArgumentException("plannedHours must not be negative");
        }
        this.hoursPerWeek = hoursPerWeek;
        this.plannedHours = plannedHours;
    }

    public boolean isSplitSubgroups() {
        return splitSubgroups;
    }

    public void setSplitSubgroups(boolean splitSubgroups) {
        this.splitSubgroups = splitSubgroups;
    }

    public RoomType getRequiredRoomType() {
        return requiredRoomType;
    }

    public void setRequiredRoomType(RoomType requiredRoomType) {
        this.requiredRoomType = requiredRoomType;
    }

    public UUID getPreferredRoomId() {
        return preferredRoomId;
    }

    public void setPreferredRoomId(UUID preferredRoomId) {
        this.preferredRoomId = preferredRoomId;
    }

    public ControlForm getControlForm() {
        return controlForm;
    }

    public void setControlForm(ControlForm controlForm) {
        this.controlForm = Objects.requireNonNull(controlForm, "controlForm");
    }

    public UUID getStreamId() {
        return streamId;
    }

    public void setStreamId(UUID streamId) {
        this.streamId = streamId;
    }

    public LoadSource getSource() {
        return source;
    }

    public void setSource(LoadSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public List<LoadItemTeacher> getTeachers() {
        return Collections.unmodifiableList(teachers);
    }

    public Optional<UUID> teacherOf(int subgroupNumber) {
        return teachers.stream()
                .filter(teacher -> teacher.getSubgroupNumber() == subgroupNumber)
                .map(LoadItemTeacher::getTeacherId)
                .findFirst();
    }

    /** Sets (or replaces) the teacher of a subgroup; 0 = whole group. */
    public void assignTeacher(int subgroupNumber, UUID teacherId) {
        Objects.requireNonNull(teacherId, "teacherId");
        teachers.stream()
                .filter(teacher -> teacher.getSubgroupNumber() == subgroupNumber)
                .findFirst()
                .ifPresentOrElse(teacher -> teacher.setTeacherId(teacherId),
                        () -> teachers.add(new LoadItemTeacher(this, subgroupNumber, teacherId)));
    }

    public void unassignTeacher(int subgroupNumber) {
        teachers.removeIf(teacher -> teacher.getSubgroupNumber() == subgroupNumber);
    }
}
