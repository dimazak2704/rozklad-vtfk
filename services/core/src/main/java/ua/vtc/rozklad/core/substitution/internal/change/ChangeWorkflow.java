package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.substitution.ApprovalMode;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeHistory;
import ua.vtc.rozklad.core.substitution.ChangeHistoryRepository;
import ua.vtc.rozklad.core.timetable.DatedLessonRefresh;

/**
 * Status transitions of changes (DOMAIN §7): every transition is validated by
 * {@link ChangeStatusMachine}, written to {@code change_history} and announced with an
 * integration event. The caller saves the change first (history rows reference it).
 */
@Component
public class ChangeWorkflow {

    private final ChangeHistoryRepository history;
    private final ChangeNotifications notifications;
    private final SettingsService settings;
    private final DatedLessonRefresh lessonRefresh;
    private final Clock clock;

    public ChangeWorkflow(ChangeHistoryRepository history, ChangeNotifications notifications,
                          SettingsService settings, DatedLessonRefresh lessonRefresh, Clock clock) {
        this.history = history;
        this.notifications = notifications;
        this.settings = settings;
        this.lessonRefresh = lessonRefresh;
        this.clock = clock;
    }

    /**
     * Approval rules for a new change. Without a teacher to assign it is confirmed at once (AUTO). With
     * one, the setting decides: CONFIRM waits for the teacher (DRAFT → PENDING_CONFIRMATION, deadline,
     * request); NOTIFY confirms at once and informs the teacher (no deadline, no reminders); AUTO
     * confirms at once without a message. One history entry records the creation.
     */
    public void open(Change change, String actor) {
        ApprovalMode mode = change.getNewTeacherId() == null ? ApprovalMode.AUTO : switch (settings.approvalMode()) {
            case AUTO -> ApprovalMode.AUTO;
            case CONFIRM -> ApprovalMode.CONFIRM;
            case NOTIFY -> ApprovalMode.NOTIFY;
        };
        ChangeStatus target = mode == ApprovalMode.CONFIRM ? ChangeStatus.PENDING_CONFIRMATION : ChangeStatus.CONFIRMED;
        ChangeStatusMachine.requireTransition(change.getStatus(), target);
        change.setStatus(target);
        change.setApprovalMode(mode);
        if (mode == ApprovalMode.CONFIRM) {
            change.startConfirmationRound(clock.instant().plus(settings.current().confirmTimeout()));
        }
        history.save(new ChangeHistory(change.getId(), clock.instant(), actor, null, target, switch (mode) {
            case CONFIRM -> "Створено, очікує підтвердження викладача";
            case NOTIFY -> "Створено і підтверджено, викладачу надіслано повідомлення";
            case AUTO -> "Створено і підтверджено";
        }));
        switch (mode) {
            case CONFIRM -> notifications.confirmationRequested(change);
            case NOTIFY -> {
                notifications.statusChanged(change);
                notifications.confirmationRequested(change);
            }
            case AUTO -> notifications.statusChanged(change);
        }
    }

    /**
     * Validated transition with history and {@code substitution.status-changed}. A cancelled or
     * declined change no longer keeps its lessons, so their group-day is brought back in line with
     * the timetable and the calendar (e.g. a lesson of an archived version kept for this decision
     * is replaced by the current version's lesson).
     */
    public void moveTo(Change change, ChangeStatus target, String actor, String comment) {
        ChangeStatusMachine.requireTransition(change.getStatus(), target);
        transition(change, target, actor, comment);
    }

    /**
     * NOTIFY: the informed teacher cannot take a confirmed, not yet published change
     * (CONFIRMED → DECLINED). The lesson needs a decision again, exactly as after a declined request.
     */
    public void declineAssigned(Change change, String actor, String comment) {
        if (change.getApprovalMode() != ApprovalMode.NOTIFY) {
            throw new BusinessRuleException(ChangeStatusMachine.ILLEGAL_TRANSITION,
                    "Підтверджену заміну може відхилити лише викладач, якому про неї повідомили.");
        }
        ChangeStatusMachine.requireTeacherDecline(change.getStatus());
        transition(change, ChangeStatus.DECLINED, actor, comment);
    }

    /**
     * An answer that keeps the status (NOTIFY: «Добре», or «Не можу» after publication): a history
     * entry with the same status on both sides and {@code substitution.teacher-replied}.
     */
    public void recordAnswerWithoutTransition(Change change, String actor, String comment) {
        history.save(new ChangeHistory(change.getId(), clock.instant(), actor, change.getStatus(), change.getStatus(),
                comment));
        notifications.teacherReplied(change);
    }

    private void transition(Change change, ChangeStatus target, String actor, String comment) {
        ChangeStatus from = change.getStatus();
        change.setStatus(target);
        history.save(new ChangeHistory(change.getId(), clock.instant(), actor, from, target, comment));
        notifications.statusChanged(change);
        if ((target == ChangeStatus.CANCELLED || target == ChangeStatus.DECLINED)
                && (change.getOriginalLessonId() != null || change.getSwapLessonId() != null)) {
            DatedLessonRefresh.Result refreshed = lessonRefresh.refresh(List.of(change.getDate()),
                    List.of(change.getGroupId()));
            change.forgetLessons(refreshed.removedLessonIds());
        }
    }

    /**
     * Asks the assigned teacher (again): a new confirmation round with a fresh deadline. Allowed for
     * DRAFT, PENDING_CONFIRMATION (re-send) and DECLINED (ask once more, whatever mode the change had).
     */
    public void requestConfirmation(Change change, String actor) {
        if (change.getNewTeacherId() == null) {
            throw new BusinessRuleException("CHANGE_WITHOUT_TEACHER",
                    "Ця зміна не призначає викладача, тому підтвердження не потрібне.");
        }
        ChangeStatus from = change.getStatus();
        if (from != ChangeStatus.PENDING_CONFIRMATION) {
            ChangeStatusMachine.requireTransition(from, ChangeStatus.PENDING_CONFIRMATION);
            change.setStatus(ChangeStatus.PENDING_CONFIRMATION);
            history.save(new ChangeHistory(change.getId(), clock.instant(), actor, from,
                    ChangeStatus.PENDING_CONFIRMATION, "Запит на підтвердження надіслано повторно"));
        }
        change.setApprovalMode(ApprovalMode.CONFIRM);
        change.startConfirmationRound(clock.instant().plus(settings.current().confirmTimeout()));
        notifications.confirmationRequested(change);
    }
}
