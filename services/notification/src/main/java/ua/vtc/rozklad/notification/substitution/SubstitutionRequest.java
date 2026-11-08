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
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;

/**
 * A substitution the bot sent to a teacher (keyed by core's change id): a request to confirm (CONFIRM,
 * starts PENDING_CONFIRMATION) or a notice about an assigned change (NOTIFY, starts CONFIRMED and waits for
 * «Добре» / «Не можу» in {@code teacherAnswer}).
 */
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
    @Column(name = "mode", nullable = false, length = 16)
    private SubstitutionRequestMode mode = SubstitutionRequestMode.CONFIRM;

    /** NOTIFY: the teacher's answer to the notice ({@code null} until he answers). */
    @Enumerated(EnumType.STRING)
    @Column(name = "teacher_answer", length = 16)
    private ConfirmationDecision teacherAnswer;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_kind", length = 24)
    private ChangeKind changeKind;

    @Column(name = "original_discipline_name", length = 300)
    private String originalDisciplineName;

    @Column(name = "reason_code", length = 16)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ChangeStatus status;

    @Column(name = "reminded_at")
    private Instant remindedAt;

    /** The request could not be sent: the teacher had no Telegram (null once it was sent). */
    @Column(name = "no_telegram_at")
    private Instant noTelegramAt;

    /** Admins were told (or deliberately not told) about the missing Telegram. */
    @Column(name = "admin_warned_at")
    private Instant adminWarnedAt;

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
        if (!event.teacherId().equals(teacherId)) {
            // інший викладач — попередження про Telegram стосувалося попереднього
            this.noTelegramAt = null;
            this.adminWarnedAt = null;
        }
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
        this.mode = event.effectiveMode();
        this.changeKind = event.kind();
        this.originalDisciplineName = event.originalDisciplineName();
        this.reasonCode = event.reasonCode();
        // повідомлення NOTIFY — про вже підтверджену заміну; прохання CONFIRM чекає відповіді
        this.status = mode == SubstitutionRequestMode.NOTIFY ? ChangeStatus.CONFIRMED : ChangeStatus.PENDING_CONFIRMATION;
        this.teacherAnswer = null;
        this.remindedAt = null;
        this.updatedAt = now;
    }

    /**
     * NOTIFY: the teacher's answer as core recorded it, with the status it left.
     *
     * @return true when the answer or the status really changed
     */
    boolean recordAnswer(ConfirmationDecision answer, ChangeStatus newStatus, Instant now) {
        if (answer == teacherAnswer && newStatus == status) {
            return false;
        }
        this.teacherAnswer = answer;
        this.status = newStatus;
        this.updatedAt = now;
        return true;
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

    /** The request was not sent because the teacher has no Telegram; the first moment counts. */
    void markNoTelegram(Instant now) {
        if (noTelegramAt == null) {
            this.noTelegramAt = now;
            this.updatedAt = now;
        }
    }

    /** The request reached the teacher's chat after all (he connected Telegram in the meantime). */
    void markSent(Instant now) {
        if (noTelegramAt != null) {
            this.noTelegramAt = null;
            this.updatedAt = now;
        }
    }

    void markAdminWarned(Instant now) {
        this.adminWarnedAt = now;
        this.updatedAt = now;
    }

    public boolean isWithoutTelegram() {
        return noTelegramAt != null;
    }

    public boolean isPending() {
        return status == ChangeStatus.PENDING_CONFIRMATION;
    }

    /** NOTIFY: a notice about an assigned change rather than a request to confirm. */
    public boolean isNotice() {
        return mode == SubstitutionRequestMode.NOTIFY;
    }

    /** The teacher can still answer: a pending request, or a notice of a change in force without an answer. */
    public boolean isAwaitingAnswer() {
        if (!isNotice()) {
            return isPending();
        }
        return teacherAnswer == null && (status == ChangeStatus.CONFIRMED || status == ChangeStatus.APPLIED);
    }

    public SubstitutionRequestMode getMode() {
        return mode;
    }

    public ConfirmationDecision getTeacherAnswer() {
        return teacherAnswer;
    }

    public ChangeKind getChangeKind() {
        return changeKind;
    }

    public String getOriginalDisciplineName() {
        return originalDisciplineName;
    }

    public String getReasonCode() {
        return reasonCode;
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
