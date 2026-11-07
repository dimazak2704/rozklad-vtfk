package ua.vtc.rozklad.core.shared.messaging;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplication of incoming integration events. Typical consumer:
 * <pre>{@code
 * @RabbitListener(queues = RozkladEvents.Queues.CORE_SCHEDULER_RESULTS)
 * @Transactional
 * void on(GenerationCompleted event) {
 *     if (!processedEvents.markProcessed(event.eventId())) return; // дублікат
 *     ...
 * }
 * }</pre>
 * The mark joins the consumer transaction, so a failed handler leaves the event unprocessed.
 */
@Component
public class ProcessedEvents {

    static final Duration RETENTION = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(ProcessedEvents.class);

    private final ProcessedEventRepository repository;
    private final Clock clock;

    public ProcessedEvents(ProcessedEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @return {@code true} when the event is seen for the first time and must be handled */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId) {
        return repository.insertIfAbsent(eventId, clock.instant()) == 1;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Europe/Kyiv")
    @Transactional
    public void purgeOld() {
        int removed = repository.deleteProcessedBefore(clock.instant().minus(RETENTION));
        if (removed > 0) {
            log.info("Removed {} processed event ids older than {}", removed, RETENTION);
        }
    }
}
