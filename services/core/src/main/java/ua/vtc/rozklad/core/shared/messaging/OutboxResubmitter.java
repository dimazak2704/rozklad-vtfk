package ua.vtc.rozklad.core.shared.messaging;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivery while core runs: failed publications (broker down, nack, unroutable, no confirm, or marked
 * stale by Modulith's staleness monitor) are sent again every {@code app.outbox.resubmit-interval},
 * not only on restart. Consumers deduplicate by {@code eventId}, so a repeat is harmless. Completed
 * publications are purged nightly.
 */
@Component
class OutboxResubmitter {

    private static final Logger log = LoggerFactory.getLogger(OutboxResubmitter.class);
    private static final int BATCH_SIZE = 100;

    private final FailedEventPublications failed;
    private final CompletedEventPublications completed;
    private final OutboxProperties properties;

    OutboxResubmitter(FailedEventPublications failed, CompletedEventPublications completed,
                      OutboxProperties properties) {
        this.failed = failed;
        this.completed = completed;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.resubmit-interval:1m}",
            initialDelayString = "${app.outbox.resubmit-interval:1m}")
    void resubmitFailed() {
        AtomicInteger count = new AtomicInteger();
        failed.resubmit(ResubmissionOptions.defaults()
                .withMinAge(properties.resubmitMinAge())
                .withBatchSize(BATCH_SIZE)
                .withFilter(publication -> {
                    count.incrementAndGet();
                    return true;
                }));
        if (count.get() > 0) {
            log.warn("Resubmitted {} failed outbox publications", count.get());
        }
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Europe/Kyiv")
    void purgeCompleted() {
        completed.deletePublicationsOlderThan(properties.completedRetention());
    }
}
