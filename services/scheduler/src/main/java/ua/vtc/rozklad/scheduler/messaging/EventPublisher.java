package ua.vtc.rozklad.scheduler.messaging;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.RozkladEvents;

/**
 * Publishes integration events to {@link RozkladEvents#EXCHANGE}. Events describe committed state,
 * so they leave only after the current transaction commits. Important events wait for the broker's
 * per-message confirmation ({@code publisher-confirm-type: correlated}).
 */
@Component
public class EventPublisher {

    static final long CONFIRM_TIMEOUT_MS = 5_000;

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public EventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Best-effort event (e.g. progress): sent after the commit, a failure is only logged because the
     * next event supersedes it.
     */
    public void publishAfterCommit(RozkladEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendQuietly(event);
                }
            });
        } else {
            sendQuietly(event);
        }
    }

    /**
     * Sends the event and waits until the broker confirms this very message.
     *
     * @return {@code false} when the broker is unavailable, rejected the message or did not answer in time
     */
    public boolean publishConfirmed(RozkladEvent event) {
        String routingKey = RozkladEvents.routingKey(event);
        CorrelationData correlation = new CorrelationData(event.eventId().toString());
        try {
            rabbitTemplate.convertAndSend(RozkladEvents.EXCHANGE, routingKey, event, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                log.warn("Event {} ({}) was rejected by the broker: {}", event.eventId(), routingKey, confirm.reason());
            }
            return confirm.ack();
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            log.warn("Event {} ({}) was not confirmed by the broker: {}", event.eventId(), routingKey, e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void sendQuietly(RozkladEvent event) {
        String routingKey = RozkladEvents.routingKey(event);
        try {
            rabbitTemplate.convertAndSend(RozkladEvents.EXCHANGE, routingKey, event);
        } catch (AmqpException e) {
            log.warn("Event {} ({}) was not sent: {}", event.eventId(), routingKey, e.getMessage());
        }
    }
}
