package ua.vtc.rozklad.core.substitution.internal.change;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.shared.ref.DisciplineRef;
import ua.vtc.rozklad.core.shared.ref.GroupRef;
import ua.vtc.rozklad.core.shared.ref.RoomRef;
import ua.vtc.rozklad.core.shared.ref.TeacherRef;
import ua.vtc.rozklad.core.substitution.ApprovalMode;
import ua.vtc.rozklad.core.substitution.ReasonCode;

/**
 * {@code ChangeDto} of CONTRACTS.md §2. Additive fields (CHANGELOG-CONTRACTS): {@code approvalMode},
 * {@code originalLessonId}, {@code swapLessonId}, {@code swapFromPair}, {@code printedReason}
 * (what the order prints), {@code responseComment}, {@code remindedAt}, {@code createdBy},
 * {@code createdAt} and {@code version} (send it back in PUT for optimistic locking).
 */
public record ChangeDto(
        UUID id,
        ChangeKind kind,
        ChangeStatus status,
        LocalDate date,
        int pairFrom,
        int pairTo,
        GroupRef group,
        int subgroup,
        Parties original,
        @JsonProperty("new") Parties replacement,
        ReasonCode reasonCode,
        String reasonText,
        UUID absenceId,
        Instant confirmDeadline,
        Instant respondedAt,
        UUID orderId,
        List<HistoryEntry> history,
        ApprovalMode approvalMode,
        UUID originalLessonId,
        UUID swapLessonId,
        Integer swapFromPair,
        String printedReason,
        String responseComment,
        Instant remindedAt,
        String createdBy,
        Instant createdAt,
        long version) {

    public record Parties(DisciplineRef discipline, TeacherRef teacher, RoomRef room) {
    }

    public record HistoryEntry(Instant at, String actor, ChangeStatus from, ChangeStatus to, String comment) {
    }
}
