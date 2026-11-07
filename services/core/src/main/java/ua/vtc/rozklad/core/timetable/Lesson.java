package ua.vtc.rozklad.core.timetable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;

/**
 * Заняття на дату: the single source for schedules, changes and accounting. Deferred exclusion
 * constraints guard SCHEDULED lessons per teacher, exclusive room and cohort slot (checked at
 * commit or by {@link LessonRepository#checkSlotConstraintsNow()}); whole-group vs subgroup
 * overlap is checked in code under {@link LessonRepository#lockGroupDay}.
 */
@Entity
@Table(name = "lesson")
public class Lesson extends BaseEntity {

    @Column(name = "semester_id", nullable = false, updatable = false)
    private UUID semesterId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "template_lesson_id")
    private UUID templateLessonId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "pair_number", nullable = false)
    private int pairNumber;

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

    @Column(name = "room_exclusive", nullable = false)
    private boolean roomExclusive = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LessonStatus status = LessonStatus.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private LessonOrigin origin;

    @Column(name = "change_id")
    private UUID changeId;

    @Column(name = "stream_id")
    private UUID streamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lesson_kind", nullable = false, length = 16)
    private LessonKind lessonKind = LessonKind.MIXED;

    @Column(name = "online", nullable = false)
    private boolean online;

    protected Lesson() {
    }

    public Lesson(UUID semesterId, LocalDate date, int pairNumber, UUID groupId, int subgroupNumber,
                  UUID disciplineId, LessonOrigin origin) {
        this.semesterId = Objects.requireNonNull(semesterId, "semesterId");
        moveTo(date, pairNumber);
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        if (subgroupNumber < 0) {
            throw new IllegalArgumentException("subgroup number must be >= 0");
        }
        this.subgroupNumber = subgroupNumber;
        setDisciplineId(disciplineId);
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    public UUID getSemesterId() {
        return semesterId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getTemplateLessonId() {
        return templateLessonId;
    }

    /** Links a materialized lesson to the template it came from. */
    public void setTemplate(UUID versionId, UUID templateLessonId) {
        this.versionId = versionId;
        this.templateLessonId = templateLessonId;
    }

    public LocalDate getDate() {
        return date;
    }

    public int getPairNumber() {
        return pairNumber;
    }

    public void moveTo(LocalDate date, int pairNumber) {
        if (pairNumber < 1 || pairNumber > 8) {
            throw new IllegalArgumentException("pair must be 1..8");
        }
        this.date = Objects.requireNonNull(date, "date");
        this.pairNumber = pairNumber;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public int getSubgroupNumber() {
        return subgroupNumber;
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

    public boolean isRoomExclusive() {
        return roomExclusive;
    }

    /** {@code exclusive} must be {@code room.maxParallel == 1} so that the room index stays correct. */
    public void setRoom(UUID roomId, boolean exclusive) {
        this.roomId = roomId;
        this.roomExclusive = exclusive;
    }

    public LessonStatus getStatus() {
        return status;
    }

    public boolean isScheduled() {
        return status == LessonStatus.SCHEDULED;
    }

    public void cancel() {
        this.status = LessonStatus.CANCELLED;
    }

    public void restore() {
        this.status = LessonStatus.SCHEDULED;
    }

    public LessonOrigin getOrigin() {
        return origin;
    }

    public UUID getChangeId() {
        return changeId;
    }

    public void setChangeId(UUID changeId) {
        this.changeId = changeId;
    }

    public UUID getStreamId() {
        return streamId;
    }

    public void setStreamId(UUID streamId) {
        this.streamId = streamId;
    }

    public LessonKind getLessonKind() {
        return lessonKind;
    }

    public void setLessonKind(LessonKind lessonKind) {
        this.lessonKind = Objects.requireNonNull(lessonKind, "lessonKind");
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }
}
