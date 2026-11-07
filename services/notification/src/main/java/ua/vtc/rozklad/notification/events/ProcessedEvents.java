package ua.vtc.rozklad.notification.events;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplication of incoming events. The mark joins the handler's transaction, so a failed handler
 * leaves the event unprocessed (RabbitMQ redelivers it, then it goes to the DLQ).
 */
@Component
public class ProcessedEvents {

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

    @Transactional
    public int purgeBefore(Instant before) {
        return repository.deleteProcessedBefore(before);
    }
}
