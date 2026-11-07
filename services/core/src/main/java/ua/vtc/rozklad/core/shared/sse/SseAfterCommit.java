package ua.vtc.rozklad.core.shared.sse;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pushes SSE events only after the current transaction commits, so clients never refetch data
 * that is not visible yet and a rolled-back change is never announced. Without an active
 * transaction the event is sent at once.
 */
@Component
public class SseAfterCommit {

    private final SseBroadcaster broadcaster;

    public SseAfterCommit(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public void sendToUser(UUID userId, String eventType, Object data) {
        if (userId != null) {
            afterCommit(() -> broadcaster.sendToUser(userId, eventType, data));
        }
    }

    public void sendToRole(String role, String eventType, Object data) {
        afterCommit(() -> broadcaster.sendToRole(role, eventType, data));
    }

    public void broadcast(String eventType, Object data) {
        afterCommit(() -> broadcaster.broadcast(eventType, data));
    }

    private static void afterCommit(Runnable action) {
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
