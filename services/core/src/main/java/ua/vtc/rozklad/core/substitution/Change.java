package ua.vtc.rozklad.core.substitution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;

/**
 * Зміна в розкладі на дату (заміна, скасування, додаткова пара, …) for a group cohort and pairs
 * {@code [pairFrom, pairTo]}. {@code orig*} snapshot the original lesson, {@code new*} the
 * replacement. Status transitions (DOMAIN §7) are implemented by the substitution services; every
 * transition must be recorded in {@link ChangeHistory}.
 */
@Entity
@Table(name = "change")
public class Change extends BaseEntity {

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "pair_from", nullable = false)
    private int pairFrom;

    @Column(name = "pair_to", nullable = false)
    private int pairTo;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "subgroup_number", nullable = false)
    private int subgroupNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private ChangeKind kind;

    @Column(name = "original_lesson_id")
    private UUID originalLessonId;

    @Column(name = "orig_discipline_id")
    private UUID origDisciplineId;

    @Column(name = "orig_teacher_id")
    private UUID origTeacherId;

    @Column(name = "orig_room_id")
    private UUID origRoomId;

    @Column(name = "new_discipline_id")
    private UUID newDisciplineId;

    @Column(name = "new_teacher_id")
    private UUID newTeacherId;

    @Column(name = "new_room_id")
    private UUID newRoomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 16)
    private ReasonCode reasonCode;

    @Column(name = "reason_text", length = 100)
    private String reasonText;

    @Column(name = "absence_id")
    private UUID absenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ChangeStatus status = ChangeStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode", nullable = false, length = 16)
    private ApprovalMode approvalMode = ApprovalMode.AUTO;

    @Column(name = "confirm_deadline")
    private Instant confirmDeadline;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "response_comment", columnDefinition = "text")
    private String responseComment;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "swap_lesson_id")
    private UUID swapLessonId;

    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    protected Change() {
    }

    public Change(ChangeKind kind, LocalDate date, int pairFrom, int pairTo, UUID groupId, int subgroupNumber,
                  String createdBy) {
        this.kind = Objects.requireNonNull(kind, "kind");
        setSlot(date, pairFrom, pairTo);
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        if (subgroupNumber < 0) {
            throw new IllegalArgumentException("subgroup number must be >= 0");
        }
        this.subgroupNumber = subgroupNumber;
        this.createdBy = createdBy;
    }

    public LocalDate getDate() {
        return date;
    }

    public int getPairFrom() {
        return pairFrom;
    }

    public int getPairTo() {
        return pairTo;
    }

    public void setSlot(LocalDate date, int pairFrom, int pairTo) {
        if (pairFrom < 1 || pairTo > 8 || pairTo < pairFrom) {
            throw new IllegalArgumentException("invalid pairs " + pairFrom + ".." + pairTo);
        }
        this.date = Objects.requireNonNull(date, "date");
        this.pairFrom = pairFrom;
        this.pairTo = pairTo;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public int getSubgroupNumber() {
        return subgroupNumber;
    }

    public ChangeKind getKind() {
        return kind;
    }

    public UUID getOriginalLessonId() {
        return originalLessonId;
    }

    public UUID getOrigDisciplineId() {
        return origDisciplineId;
    }

    public UUID getOrigTeacherId() {
        return origTeacherId;
    }

    public UUID getOrigRoomId() {
        return origRoomId;
    }

    /** Snapshot of the lesson being changed ({@code lessonId} is {@code null} for ADDITIONAL). */
    public void setOriginal(UUID lessonId, UUID disciplineId, UUID teacherId, UUID roomId) {
        this.originalLessonId = lessonId;
        this.origDisciplineId = disciplineId;
        this.origTeacherId = teacherId;
        this.origRoomId = roomId;
    }

    public UUID getNewDisciplineId() {
        return newDisciplineId;
    }

    public UUID getNewTeacherId() {
        return newTeacherId;
    }

    public UUID getNewRoomId() {
        return newRoomId;
    }

    public void setReplacement(UUID disciplineId, UUID teacherId, UUID roomId) {
        this.newDisciplineId = disciplineId;
        this.newTeacherId = teacherId;
        this.newRoomId = roomId;
    }

    public ReasonCode getReasonCode() {
        return reasonCode;
    }

    public String getReasonText() {
        return reasonText;
    }

    public void setReason(ReasonCode reasonCode, String reasonText) {
        this.reasonCode = reasonCode;
        this.reasonText = reasonText;
    }

    /**
     * Reason as printed in the order's «Причина заміни» column (DOMAIN §7–§8): BLOCK, ADDITIONAL
     * and room-only rows have none, a finished discipline is always «Вичит.», OTHER prints the free
     * text (may be empty), every other code its abbreviation («Відп.», «Зв.зам.», «Відр.»).
     */
    public String printedReason() {
        return ReasonCode.printed(kind, reasonCode, reasonText);
    }

    public UUID getAbsenceId() {
        return absenceId;
    }

    public void setAbsenceId(UUID absenceId) {
        this.absenceId = absenceId;
    }

    public ChangeStatus getStatus() {
        return status;
    }

    /** Raw status setter for the workflow service, which validates transitions and writes history. */
    public void setStatus(ChangeStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public ApprovalMode getApprovalMode() {
        return approvalMode;
    }

    public void setApprovalMode(ApprovalMode approvalMode) {
        this.approvalMode = Objects.requireNonNull(approvalMode, "approvalMode");
    }

    public Instant getConfirmDeadline() {
        return confirmDeadline;
    }

    public void setConfirmDeadline(Instant confirmDeadline) {
        this.confirmDeadline = confirmDeadline;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public String getResponseComment() {
        return responseComment;
    }

    public void recordResponse(Instant respondedAt, String responseComment) {
        this.respondedAt = Objects.requireNonNull(respondedAt, "respondedAt");
        this.responseComment = responseComment;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    /** SWAP: the later lesson of the group that moves into this change's slot. */
    public UUID getSwapLessonId() {
        return swapLessonId;
    }

    public void setSwapLessonId(UUID swapLessonId) {
        this.swapLessonId = swapLessonId;
    }

    /** When the overdue-confirmation reminder was sent for the current confirmation round. */
    public Instant getRemindedAt() {
        return remindedAt;
    }

    public void markReminded(Instant at) {
        this.remindedAt = Objects.requireNonNull(at, "at");
    }

    /** Starts a new confirmation round: new deadline, no reminder sent yet. */
    public void startConfirmationRound(Instant deadline) {
        this.confirmDeadline = Objects.requireNonNull(deadline, "deadline");
        this.remindedAt = null;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public int pairCount() {
        return pairTo - pairFrom + 1;
    }

    public boolean coversPair(int pair) {
        return pair >= pairFrom && pair <= pairTo;
    }

    /** DRAFT, PENDING_CONFIRMATION or CONFIRMED: a decision that is still in force but not applied yet. */
    public boolean isOpen() {
        return status == ChangeStatus.DRAFT || status == ChangeStatus.PENDING_CONFIRMATION
                || status == ChangeStatus.CONFIRMED;
    }
}
