package ua.vtc.rozklad.scheduler.messaging;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplication of incoming integration events by {@code eventId}. The mark joins the consumer's
 * transaction, so a handler that fails leaves the event unprocessed and the redelivery is handled.
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
}
