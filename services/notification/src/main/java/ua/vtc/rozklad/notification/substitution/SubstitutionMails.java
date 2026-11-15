package ua.vtc.rozklad.notification.substitution;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.email.EmailKind;
import ua.vtc.rozklad.notification.email.EmailNotices;
import ua.vtc.rozklad.notification.email.MailModels.AdminSubstitutionMail;
import ua.vtc.rozklad.notification.email.MailModels.SubstitutionMail;
import ua.vtc.rozklad.notification.email.MailModels.UnreachableMail;

/**
 * E-mail intents of the substitution flow: the same moments as the Telegram messages, independent of whether
 * the teacher has Telegram. Only intents are written (in the caller's transaction); who gets a letter is decided
 * later by the e-mail worker.
 */
@Component
class SubstitutionMails {

    private final EmailNotices notices;

    SubstitutionMails(EmailNotices notices) {
        this.notices = notices;
    }

    /** A letter to {@code teacherId} about {@code request} as it reads now. */
    void toTeacher(EmailKind kind, SubstitutionRequest request, UUID teacherId, String whatChanged,
                   boolean reassigned, UUID eventId, String dedupKey) {
        if (!notices.enabled()) {
            return;
        }
        notices.toTeacher(kind, teacherId, model(request, whatChanged, reassigned), request.getChangeId(),
                request.getLessonDate(), request.getEndsAt(), eventId, dedupKey);
    }

    /** A refusal, an objection or an unanswered request for the dispatchers; {@code request} may be null. */
    void toAdmins(EmailKind kind, String teacherName, UUID changeId, SubstitutionRequest request, LocalDate date,
                  Integer pair, String groupCode, Instant deadline, UUID eventId, String dedupKey) {
        if (!notices.enabled()) {
            return;
        }
        AdminSubstitutionMail model = request != null
                ? new AdminSubstitutionMail(changeId, teacherName, request.getLessonDate(), request.getPair(),
                request.getGroupCode(), request.getDisciplineName(), deadline)
                : new AdminSubstitutionMail(changeId, teacherName, date, pair, groupCode, null, deadline);
        notices.toAdmins(kind, model, changeId, eventId, dedupKey);
    }

    /** One warning per teacher and date about requests that never reached him in Telegram. */
    void teacherUnreachable(UUID teacherId, String teacherName, LocalDate date, boolean notice,
                            List<SubstitutionRequest> requests, String dedupKey) {
        if (!notices.enabled()) {
            return;
        }
        List<String> lessons = requests.stream().map(SubstitutionMails::lesson).toList();
        notices.toAdmins(EmailKind.TEACHER_UNREACHABLE, new UnreachableMail(teacherId, teacherName, date, notice,
                lessons), null, null, dedupKey);
    }

    static SubstitutionMail model(SubstitutionRequest request, String whatChanged, boolean reassigned) {
        return new SubstitutionMail(request.getChangeId(), request.getMode().name(), request.getLessonDate(),
                request.getPair(), request.getStartsAt(), request.getEndsAt(), request.getGroupCode(),
                request.getSubgroup(), request.getDisciplineName(),
                request.getChangeKind() == null ? null : request.getChangeKind().name(),
                request.getOriginalDisciplineName(), request.getOriginalTeacherName(), request.getReasonCode(),
                request.getRoomName(), request.getDeadline(), whatChanged, request.isConfirmedRequest(), reassigned);
    }

    /** «2 пара · 4КІ1 · Дисципліна». */
    private static String lesson(SubstitutionRequest request) {
        StringBuilder text = new StringBuilder().append(request.getPair()).append(" пара");
        if (request.getGroupCode() != null) {
            text.append(" · ").append(request.getGroupCode());
        }
        if (request.getDisciplineName() != null && !request.getDisciplineName().isBlank()) {
            text.append(" · ").append(request.getDisciplineName());
        }
        return text.toString();
    }
}
