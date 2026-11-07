package ua.vtc.rozklad.notification.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions of the delivery worker: claim, then record the outcome of each attempt. */
@Component
public class DeliveryRecorder {

    private final OutboundMessageRepository repository;
    private final ApplicationEventPublisher events;

    public DeliveryRecorder(OutboundMessageRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /** Locks due messages (one per chat) and leases them for {@code lease}. */
    @Transactional
    public List<OutboundMessage> claim(Instant now, int limit, Duration lease) {
        List<OutboundMessage> due = repository.lockDue(now, limit);
        due.forEach(message -> message.lease(now.plus(lease), now));
        return due;
    }

    /** Claims one message regardless of the queue order (test message from the admin panel). */
    @Transactional
    public Optional<OutboundMessage> claimOne(UUID id, Instant now, Duration lease) {
        return repository.lockById(id)
                .filter(message -> message.getStatus().isPending())
                .map(message -> {
                    message.lease(now.plus(lease), now);
                    return message;
                });
    }

    @Transactional
    public void markSent(UUID id, Long telegramMessageId, Instant now) {
        OutboundMessage message = load(id);
        message.markSent(telegramMessageId, now);
        events.publishEvent(new OutboundMessageSent(id, message.getPurpose(), message.getCorrelationId(),
                message.getChatId(), telegramMessageId));
    }

    @Transactional
    public void markRetry(UUID id, String error, Instant nextAttempt, boolean countsAsAttempt, Instant now) {
        load(id).markRetry(error, nextAttempt, countsAsAttempt, now);
    }

    @Transactional
    public void markFailed(UUID id, String error, boolean countsAsAttempt, Instant now) {
        load(id).markFailed(error, countsAsAttempt, now);
    }

    @Transactional
    public void defer(UUID id, Instant until, Instant now) {
        load(id).defer(until, now);
    }

    @Transactional
    public void retarget(UUID id, long newChatId, Instant now) {
        load(id).retarget(newChatId, now);
    }

    @Transactional(readOnly = true)
    public OutboundMessage get(UUID id) {
        return load(id);
    }

    private OutboundMessage load(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Outbound message disappeared: " + id));
    }
}
