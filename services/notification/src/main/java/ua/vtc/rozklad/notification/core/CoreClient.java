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
import ua.vtc.rozklad.notification.core.CoreDtos.MailRecipient;
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

    /** A visitor's schedule: core refuses a range before the public history window (404 {@code HISTORY_NOT_PUBLIC}). */
    Schedule schedule(OwnerType type, UUID ownerId, LocalDate from, LocalDate to);

    /**
     * The same schedule with the service token: core shows the bot the whole archive. Only for the bot's staff
     * chats (a teacher's chat, an admin chat); needs the sign-in service for the token.
     */
    Schedule archiveSchedule(OwnerType type, UUID ownerId, LocalDate from, LocalDate to);

    /** {@code GET /api/me} on behalf of the user who called notification (token relay). */
    CurrentUser currentUser(String bearerToken);

    /** {@code POST /api/internal/changes/{id}/reply}: 403 teacher mismatch, 409 not pending. */
    ChangeSummary replyToChange(UUID changeId, UUID teacherId, ConfirmationDecision decision, String comment);

    /** {@code POST /api/internal/absences} with {@code source = BOT}. */
    void reportAbsence(AbsenceReport report);

    /**
     * {@code GET /api/internal/teachers/{id}}: is the teacher still active in the catalog? {@link TeacherStatus#GONE}
     * when core does not know the id (404).
     */
    TeacherStatus teacherStatus(UUID teacherId);

    /**
     * {@code GET /api/internal/mail-recipients?teacherId=|accountId=|role=} (SERVICE_NOTIFICATION): the people accounts
     * of a teacher, one account, or every account with a role. An unknown teacher or account → empty list.
     */
    List<MailRecipient> mailRecipients(MailRecipientQuery query);

    /** Exactly one filter of {@code GET /api/internal/mail-recipients}. */
    record MailRecipientQuery(String parameter, String value) {

        public static MailRecipientQuery teacher(UUID teacherId) {
            return new MailRecipientQuery("teacherId", teacherId.toString());
        }

        public static MailRecipientQuery account(UUID accountId) {
            return new MailRecipientQuery("accountId", accountId.toString());
        }

        public static MailRecipientQuery role(String role) {
            return new MailRecipientQuery("role", role);
        }

        /** Cache key part: {@code teacherId:…}. */
        public String key() {
            return parameter + ":" + value;
        }
    }

    enum TeacherStatus {
        ACTIVE,
        INACTIVE,
        GONE
    }

    /** Body of {@code POST /api/internal/absences}; {@code pairs == null} means the whole day. */
    record AbsenceReport(UUID teacherId, LocalDate dateFrom, LocalDate dateTo, List<Integer> pairs,
                         AbsenceReasonCategory reasonCategory, String comment, AbsenceSource source) {
    }
}
