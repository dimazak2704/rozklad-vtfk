package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.Clock;
import java.time.LocalDate;
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
import ua.vtc.rozklad.core.substitution.ApprovalMode;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.effect.AbsentSubstitutes;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.substitution.internal.support.CurrentTeacher;

/**
 * The assigned teacher's answer to a substitution, from the cabinet or the Telegram bot (CONTRACTS
 * §3–§4). Only the assigned teacher may answer (403).
 *
 * <p>A CONFIRM request is answered while it waits (PENDING_CONFIRMATION → CONFIRMED | DECLINED; 409
 * otherwise, e.g. the dispatcher has already cancelled or confirmed it).
 *
 * <p>A NOTIFY change is already confirmed; the teacher was informed (DOMAIN §7):
 * <ul>
 *   <li>«Добре» (CONFIRM) on a CONFIRMED or APPLIED change records the answer, the status stays;</li>
 *   <li>«Не можу» (DECLINE) on a CONFIRMED change moves it to DECLINED and the lesson needs a decision
 *       again;</li>
 *   <li>«Не можу» on an APPLIED change (its order is published) never undoes the order: the objection is
 *       recorded and the dispatcher is told that an amendment is needed;</li>
 *   <li>repeating the answer already recorded returns the change as it is (a double tap); any other
 *       answer once the change is declined, objected to or cancelled is 409.</li>
 * </ul>
 * Answers take the date lock before they read the change, like the dispatcher and the publication, so
 * an answer racing with a publication sees the published state and takes the objection path.
 */
@Service
@Transactional
public class ChangeReplies {

    static final String ACKNOWLEDGED = "Викладач побачив заміну";
    static final String DECLINED_BY_TEACHER = "Викладач не може провести пару";
    static final String OBJECTED = "Викладач не може провести пару, а заміна вже в розпорядженні: "
            + "потрібне доповнення";

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
        LocalDate date = changes.findDateById(changeId).orElseThrow(() -> NotFoundException.of("Зміну", changeId));
        // той самий порядок замків, що й у диспетчера та публікації (дата, потім рядок зміни); зміну читаємо
        // вже під замком, тож бачимо те, що встиг закомітити попередній власник (скасування, публікацію)
        changes.lockDecisionsOf(date);
        Change change = changes.findById(changeId).orElseThrow(() -> NotFoundException.of("Зміну", changeId));
        if (!teacherId.equals(change.getNewTeacherId())) {
            throw new AccessDeniedException("Заміна призначена іншому викладачу");
        }
        String text = comment == null || comment.isBlank() ? null : comment.strip();
        if (change.getApprovalMode() == ApprovalMode.NOTIFY) {
            return answerNotice(change, decision, text, actor);
        }
        if (change.getStatus() != ChangeStatus.PENDING_CONFIRMATION) {
            throw new ConflictException("Заміна вже не очікує відповіді (статус «%s»)."
                    .formatted(ChangeStatusMachine.label(change.getStatus())));
        }
        if (decision == ConfirmationDecision.CONFIRM) {
            // відсутність могли зареєструвати вже після запиту: відсутній викладач не може взяти пару
            List<ConflictDto> conflicts = absentSubstitutes.conflicts(change);
            if (!conflicts.isEmpty()) {
                throw ConflictException.schedule(conflicts);
            }
        }
        change.recordAnswer(decision, clock.instant(), text);
        if (decision == ConfirmationDecision.CONFIRM) {
            workflow.moveTo(change, ChangeStatus.CONFIRMED, actor, text != null ? text : "Підтверджено викладачем");
        } else {
            workflow.moveTo(change, ChangeStatus.DECLINED, actor, text != null ? text : "Викладач відмовився");
        }
        return finish(change);
    }

    /** NOTIFY: the change is in force already; the answer is recorded, «Не можу» before publication declines it. */
    private ChangeDto answerNotice(Change change, ConfirmationDecision decision, String text, String actor) {
        ChangeStatus status = change.getStatus();
        boolean inForce = status == ChangeStatus.CONFIRMED || status == ChangeStatus.APPLIED;
        if (decision == change.getTeacherAnswer() && (inForce || status == ChangeStatus.DECLINED)) {
            // повторне натискання тієї самої відповіді: нічого не змінюємо і не сповіщаємо вдруге
            return views.toDto(change);
        }
        if (!inForce) {
            throw new ConflictException(status == ChangeStatus.DECLINED
                    ? "Ви вже відмовилися від цієї заміни. Диспетчер обере інший варіант."
                    : "Заміна вже не діє (статус «%s»).".formatted(ChangeStatusMachine.label(status)));
        }
        if (change.getTeacherAnswer() == ConfirmationDecision.DECLINE) {
            throw new ConflictException("Ви вже повідомили, що не можете провести цю пару. Диспетчер внесе доповнення.");
        }
        change.recordAnswer(decision, clock.instant(), text);
        if (decision == ConfirmationDecision.CONFIRM) {
            workflow.recordAnswerWithoutTransition(change, actor, text != null ? text : ACKNOWLEDGED);
        } else if (status == ChangeStatus.CONFIRMED) {
            workflow.declineAssigned(change, actor, text != null ? text : DECLINED_BY_TEACHER);
        } else {
            // розпорядження вже опубліковане: мовчки його не скасовуємо, диспетчер готує доповнення
            workflow.recordAnswerWithoutTransition(change, actor, text != null ? OBJECTED + ". " + text : OBJECTED);
        }
        return finish(change);
    }

    private ChangeDto finish(Change change) {
        changes.flush();
        notifications.pushUpdate(change);
        return views.toDto(change);
    }
}
