package ua.vtc.rozklad.notification.documents;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Renders the picture of a just published order in the background after the transaction commits,
 * so the delivery worker finds it in the cache instead of waiting for LibreOffice.
 */
@Component
public class DocumentPrerenderer {

    private static final Logger log = LoggerFactory.getLogger(DocumentPrerenderer.class);

    private final OrderDocuments documents;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "document-prerender");
        thread.setDaemon(true);
        return thread;
    });

    public DocumentPrerenderer(OrderDocuments documents) {
        this.documents = documents;
    }

    public void pngAfterCommit(UUID orderId) {
        if (!documents.canRenderPictures()) {
            return;
        }
        Runnable task = () -> {
            try {
                documents.published(orderId, DocumentFormat.PNG);
            } catch (RuntimeException e) {
                // воркер доставки спробує ще раз і запише помилку в журнал доставки
                log.warn("Pre-rendering order {} failed: {}", orderId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    /** Waits until renders queued so far are done (single thread: a marker task runs after them). */
    public boolean awaitIdle(Duration timeout) {
        try {
            executor.submit(() -> { }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        awaitIdle(Duration.ofSeconds(5));
        executor.shutdownNow();
    }
}
