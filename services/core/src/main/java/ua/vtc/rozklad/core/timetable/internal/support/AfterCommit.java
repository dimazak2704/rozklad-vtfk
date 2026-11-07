package ua.vtc.rozklad.core.timetable.internal.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an action after the current transaction commits (immediately when there is none). Used for
 * realtime SSE pushes: clients must not refetch before the data is visible, and a rolled-back
 * change must not be announced. SSE is best effort, so nothing is stored for it.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
