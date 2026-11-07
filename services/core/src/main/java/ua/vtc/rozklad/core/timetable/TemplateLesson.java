package ua.vtc.rozklad.core.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Lesson of a weekly template: weekday (1..5) × pair × parity for a group cohort
 * ({@code subgroupNumber} 0 = whole group). Conflicts are allowed in drafts and reported;
 * publication rejects hard conflicts.
 */
@Entity
@Table(name = "template_lesson")
public class TemplateLesson extends BaseEntity {

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "weekday", nullable = false)
    private int weekday;

    @Column(name = "pair_number", nullable = false)
    private int pairNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "parity", nullable = false, length = 16)
    private Parity parity = Parity.BOTH;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "subgroup_number", nullable = false)
    private int subgroupNumber;

    @Column(name = "discipline_id", nullable = false)
    private UUID disciplineId;

    @Column(name = "teacher_id")
    private UUID teacherId;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "load_item_id")
    private UUID loadItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lesson_kind", nullable = false, length = 16)
    private LessonKind lessonKind = LessonKind.MIXED;

    @Column(name = "stream_id")
    private UUID streamId;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @Column(name = "online", nullable = false)
    private boolean online;

    protected TemplateLesson() {
    }

    public TemplateLesson(UUID versionId, int weekday, int pairNumber, Parity parity, UUID groupId,
                          int subgroupNumber, UUID disciplineId) {
        this.versionId = Objects.requireNonNull(versionId, "versionId");
        moveTo(weekday, pairNumber, parity);
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        setSubgroupNumber(subgroupNumber);
        setDisciplineId(disciplineId);
    }

    public UUID getVersionId() {
        return versionId;
    }

    public int getWeekday() {
        return weekday;
    }

    public int getPairNumber() {
        return pairNumber;
    }

    public Parity getParity() {
        return parity;
    }

    public void moveTo(int weekday, int pairNumber, Parity parity) {
        if (weekday < 1 || weekday > 5 || pairNumber < 1 || pairNumber > 8) {
            throw new IllegalArgumentException("weekday must be 1..5 and pair 1..8");
        }
        this.weekday = weekday;
        this.pairNumber = pairNumber;
        this.parity = Objects.requireNonNull(parity, "parity");
    }

    public UUID getGroupId() {
        return groupId;
    }

    public void setGroupId(UUID groupId) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
    }

    public int getSubgroupNumber() {
        return subgroupNumber;
    }

    public void setSubgroupNumber(int subgroupNumber) {
        if (subgroupNumber < 0) {
            throw new IllegalArgumentException("subgroup number must be >= 0");
        }
        this.subgroupNumber = subgroupNumber;
    }

    public UUID getDisciplineId() {
        return disciplineId;
    }

    public void setDisciplineId(UUID disciplineId) {
        this.disciplineId = Objects.requireNonNull(disciplineId, "disciplineId");
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public void setTeacherId(UUID teacherId) {
        this.teacherId = teacherId;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
    }

    public UUID getLoadItemId() {
        return loadItemId;
    }

    public void setLoadItemId(UUID loadItemId) {
        this.loadItemId = loadItemId;
    }

    public LessonKind getLessonKind() {
        return lessonKind;
    }

    public void setLessonKind(LessonKind lessonKind) {
        this.lessonKind = Objects.requireNonNull(lessonKind, "lessonKind");
    }

    public UUID getStreamId() {
        return streamId;
    }

    public void setStreamId(UUID streamId) {
        this.streamId = streamId;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }
}
