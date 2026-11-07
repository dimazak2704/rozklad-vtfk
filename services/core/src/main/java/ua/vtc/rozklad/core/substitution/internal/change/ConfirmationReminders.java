package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;

/**
 * Reminds about confirmations that are pending longer than {@code substitution.confirmTimeoutMinutes}
 * (DOMAIN §7): the request is sent to the teacher again ({@code substitution.confirmation-requested}
 * with the same change id and the passed deadline) and dispatchers get an SSE update. Idempotent:
 * {@code reminded_at} marks the round in the same transaction as the event, and a concurrent run
 * loses on the optimistic lock, so every confirmation round is reminded exactly once.
 */
@Component
public class ConfirmationReminders {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationReminders.class);

    private final ChangeRepository changes;
    private final ChangeNotifications notifications;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ConfirmationReminders(ChangeRepository changes, ChangeNotifications notifications,
                                 TransactionTemplate transaction, Clock clock) {
        this.changes = changes;
        this.notifications = notifications;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.substitution.reminder-check-interval:PT1M}",
            initialDelayString = "${app.substitution.reminder-check-interval:PT1M}")
    void scheduled() {
        int sent = remindOverdue();
        if (sent > 0) {
            log.info("Sent {} reminder(s) about overdue substitution confirmations", sent);
        }
    }

    /** @return how many reminders were sent by this call */
    public int remindOverdue() {
        Instant now = clock.instant();
        List<UUID> due = transaction.execute(status -> changes.findIdsToRemind(now));
        int sent = 0;
        for (UUID changeId : due == null ? List.<UUID>of() : due) {
            try {
                if (Boolean.TRUE.equals(transaction.execute(status -> remind(changeId, now)))) {
                    sent++;
                }
            } catch (OptimisticLockingFailureException concurrent) {
                log.debug("Change {} was updated concurrently, reminder skipped", changeId);
            }
        }
        return sent;
    }

    private boolean remind(UUID changeId, Instant now) {
        Change change = changes.findById(changeId).orElse(null);
        // стан міг змінитися між вибіркою і цією транзакцією
        if (change == null || change.getStatus() != ChangeStatus.PENDING_CONFIRMATION
                || change.getRemindedAt() != null || change.getConfirmDeadline() == null
                || !change.getConfirmDeadline().isBefore(now)) {
            return false;
        }
        change.markReminded(now);
        notifications.confirmationRequested(change);
        changes.flush();
        notifications.pushUpdate(change);
        return true;
    }
}
