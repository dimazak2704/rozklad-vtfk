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
     * Approval rules for a new change: with a teacher to assign and approval mode CONFIRM it waits
     * for the teacher (DRAFT → PENDING_CONFIRMATION), otherwise it is confirmed at once
     * (DRAFT → CONFIRMED). One history entry records the creation.
     */
    public void open(Change change, String actor) {
        boolean askTeacher = change.getNewTeacherId() != null && settings.requiresConfirmation();
        ChangeStatus target = askTeacher ? ChangeStatus.PENDING_CONFIRMATION : ChangeStatus.CONFIRMED;
        ChangeStatusMachine.requireTransition(change.getStatus(), target);
        change.setStatus(target);
        change.setApprovalMode(askTeacher ? ApprovalMode.CONFIRM : ApprovalMode.AUTO);
        if (askTeacher) {
            change.startConfirmationRound(clock.instant().plus(settings.current().confirmTimeout()));
        }
        history.save(new ChangeHistory(change.getId(), clock.instant(), actor, null, target,
                askTeacher ? "Створено, очікує підтвердження викладача" : "Створено і підтверджено"));
        if (askTeacher) {
            notifications.confirmationRequested(change);
        } else {
            notifications.statusChanged(change);
        }
    }

    /**
     * Validated transition with history and {@code substitution.status-changed}. A cancelled or
     * declined change no longer keeps its lessons, so their group-day is brought back in line with
     * the timetable and the calendar (e.g. a lesson of an archived version kept for this decision
     * is replaced by the current version's lesson).
     */
    public void moveTo(Change change, ChangeStatus target, String actor, String comment) {
        ChangeStatus from = change.getStatus();
        ChangeStatusMachine.requireTransition(from, target);
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
     * DRAFT, PENDING_CONFIRMATION (re-send) and DECLINED (ask once more).
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
