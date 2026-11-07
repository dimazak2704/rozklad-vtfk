package ua.vtc.rozklad.notification.substitution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.data.domain.Persistable;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;

/** A substitution the bot asked a teacher to confirm (keyed by core's change id). */
@Entity
@Table(name = "substitution_request")
public class SubstitutionRequest implements Persistable<UUID> {

    @Id
    @Column(name = "change_id", nullable = false, updatable = false)
    private UUID changeId;

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    @Column(name = "lesson_date", nullable = false)
    private LocalDate lessonDate;

    @Column(name = "pair", nullable = false)
    private int pair;

    @Column(name = "starts_at")
    private LocalTime startsAt;

    @Column(name = "ends_at")
    private LocalTime endsAt;

    @Column(name = "group_code", length = 20)
    private String groupCode;

    @Column(name = "subgroup", nullable = false)
    private int subgroup;

    @Column(name = "discipline_name", length = 300)
    private String disciplineName;

    @Column(name = "original_teacher_name", length = 200)
    private String originalTeacherName;

    @Column(name = "room_name", length = 100)
    private String roomName;

    @Column(name = "deadline")
    private Instant deadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ChangeStatus status;

    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Transient
    private boolean persisted;

    protected SubstitutionRequest() {
    }

    static SubstitutionRequest from(SubstitutionConfirmationRequested event, Instant now) {
        SubstitutionRequest request = new SubstitutionRequest();
        request.changeId = event.changeId();
        request.createdAt = now;
        request.refresh(event, now);
        return request;
    }

    /** A new request for the same change (re-sent or reassigned): take the latest details. */
    void refresh(SubstitutionConfirmationRequested event, Instant now) {
        this.teacherId = event.teacherId();
        this.lessonDate = event.date();
        this.pair = event.pair();
        this.startsAt = event.startsAt();
        this.endsAt = event.endsAt();
        this.groupCode = event.groupCode();
        this.subgroup = event.subgroup();
        this.disciplineName = event.disciplineName();
        this.originalTeacherName = event.originalTeacherName();
        this.roomName = event.roomName();
        this.deadline = event.deadline();
        this.status = ChangeStatus.PENDING_CONFIRMATION;
        this.remindedAt = null;
        this.updatedAt = now;
    }

    /** @return true when the status really changed */
    boolean changeStatus(ChangeStatus newStatus, Instant now) {
        if (status == newStatus) {
            return false;
        }
        this.status = newStatus;
        this.updatedAt = now;
        return true;
    }

    void markReminded(Instant now) {
        this.remindedAt = now;
        this.updatedAt = now;
    }

    public boolean isPending() {
        return status == ChangeStatus.PENDING_CONFIRMATION;
    }

    @Override
    public UUID getId() {
        return changeId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }

    public UUID getChangeId() {
        return changeId;
    }

    public UUID getTeacherId() {
        return teacherId;
    }

    public LocalDate getLessonDate() {
        return lessonDate;
    }

    public int getPair() {
        return pair;
    }

    public LocalTime getStartsAt() {
        return startsAt;
    }

    public LocalTime getEndsAt() {
        return endsAt;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public int getSubgroup() {
        return subgroup;
    }

    public String getDisciplineName() {
        return disciplineName;
    }

    public String getOriginalTeacherName() {
        return originalTeacherName;
    }

    public String getRoomName() {
        return roomName;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public ChangeStatus getStatus() {
        return status;
    }
}
