package ua.vtc.rozklad.notification.core;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.notification.core.CoreDtos.BellSchedule;
import ua.vtc.rozklad.notification.core.CoreDtos.ChangeSummary;
import ua.vtc.rozklad.notification.core.CoreDtos.CurrentUser;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicGroup;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicMeta;
import ua.vtc.rozklad.notification.core.CoreDtos.PublicTeacher;
import ua.vtc.rozklad.notification.core.CoreDtos.Schedule;
import ua.vtc.rozklad.notification.core.CoreDtos.SearchHit;

/**
 * Calls to core. Public reads go without a token, internal commands with the client-credentials
 * token of {@code rozklad-notification}. Failures: {@link UpstreamUnavailableException} (network,
 * 5xx, no service token) and {@link UpstreamRejectedException} (4xx with core's Ukrainian detail).
 */
public interface CoreClient {

    PublicMeta meta();

    List<PublicGroup> groups();

    List<PublicTeacher> teachers();

    List<SearchHit> search(String query, int limit);

    BellSchedule bells(LocalDate date);

    Schedule schedule(OwnerType type, UUID ownerId, LocalDate from, LocalDate to);

    /** {@code GET /api/me} on behalf of the user who called notification (token relay). */
    CurrentUser currentUser(String bearerToken);

    /** {@code POST /api/internal/changes/{id}/reply}: 403 teacher mismatch, 409 not pending. */
    ChangeSummary replyToChange(UUID changeId, UUID teacherId, ConfirmationDecision decision, String comment);

    /** {@code POST /api/internal/absences} with {@code source = BOT}. */
    void reportAbsence(AbsenceReport report);

    /** Body of {@code POST /api/internal/absences}; {@code pairs == null} means the whole day. */
    record AbsenceReport(UUID teacherId, LocalDate dateFrom, LocalDate dateTo, List<Integer> pairs,
                         AbsenceReasonCategory reasonCategory, String comment, AbsenceSource source) {
    }
}
