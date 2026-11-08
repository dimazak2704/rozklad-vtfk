package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.effect.AbsentSubstitutes;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.CurrentTeacher;

/**
 * The assigned teacher's answer to a substitution request, from the cabinet or the Telegram bot
 * (CONTRACTS §3–§4). Only the assigned teacher may answer (403), and only while the change waits
 * for an answer (409 otherwise, e.g. the dispatcher has already cancelled or confirmed it).
 */
@Service
@Transactional
public class ChangeReplies {

    private final ChangeRepository changes;
    private final ChangeWorkflow workflow;
    private final ChangeNotifications notifications;
    private final ChangeViews views;
    private final CatalogLookup catalog;
    private final CurrentTeacher currentTeacher;
    private final AbsentSubstitutes absentSubstitutes;
    private final Clock clock;

    public ChangeReplies(ChangeRepository changes, ChangeWorkflow workflow, ChangeNotifications notifications,
                         ChangeViews views, CatalogLookup catalog, CurrentTeacher currentTeacher,
                         AbsentSubstitutes absentSubstitutes, Clock clock) {
        this.changes = changes;
        this.workflow = workflow;
        this.notifications = notifications;
        this.views = views;
        this.catalog = catalog;
        this.currentTeacher = currentTeacher;
        this.absentSubstitutes = absentSubstitutes;
        this.clock = clock;
    }

    /** Answer from the teacher's cabinet; the teacher is the one linked to the account. */
    public ChangeDto replyAsUser(UUID changeId, CurrentUser user, ConfirmationDecision decision, String comment) {
        Teacher teacher = currentTeacher.require(user);
        return reply(changeId, teacher.getId(), decision, comment, user.username());
    }

    /** Answer relayed by the Telegram bot (service account) on behalf of {@code teacherId}. */
    public ChangeDto replyFromBot(UUID changeId, UUID teacherId, ConfirmationDecision decision, String comment) {
        Teacher teacher = catalog.teacher(teacherId);
        return reply(changeId, teacher.getId(), decision, comment, teacher.getShortName() + " (Telegram)");
    }

    private ChangeDto reply(UUID changeId, UUID teacherId, ConfirmationDecision decision, String comment,
                            String actor) {
        Change change = changes.findById(changeId).orElseThrow(() -> NotFoundException.of("Зміну", changeId));
        if (!teacherId.equals(change.getNewTeacherId())) {
            throw new AccessDeniedException("Заміна призначена іншому викладачу");
        }
        if (change.getStatus() != ChangeStatus.PENDING_CONFIRMATION) {
            throw new ConflictException("Заміна вже не очікує відповіді (статус «%s»)."
                    .formatted(ChangeStatusMachine.label(change.getStatus())));
        }
        String text = comment == null || comment.isBlank() ? null : comment.strip();
        if (decision == ConfirmationDecision.CONFIRM) {
            // відсутність могли зареєструвати вже після запиту: відсутній викладач не може взяти пару
            List<ConflictDto> conflicts = absentSubstitutes.conflicts(change);
            if (!conflicts.isEmpty()) {
                throw ConflictException.schedule(conflicts);
            }
        }
        change.recordResponse(clock.instant(), text);
        if (decision == ConfirmationDecision.CONFIRM) {
            workflow.moveTo(change, ChangeStatus.CONFIRMED, actor, text != null ? text : "Підтверджено викладачем");
        } else {
            workflow.moveTo(change, ChangeStatus.DECLINED, actor, text != null ? text : "Викладач відмовився");
        }
        changes.flush();
        notifications.pushUpdate(change);
        return views.toDto(change);
    }
}
