package ua.vtc.rozklad.core.shared.messaging;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.modulith.events.support.EventExternalizationTransport;
import ua.vtc.rozklad.contracts.events.RozkladEvent;

/**
 * Sends an outbox event and completes only when the broker has taken it: a publisher confirm (ack)
 * and no return of the {@code mandatory} message. A connection failure, a nack, an unroutable message
 * (no consumer queue bound yet) or no confirm within {@code confirmTimeout} fail the future, so Spring
 * Modulith marks the publication FAILED instead of COMPLETED and {@link OutboxResubmitter} sends it
 * again. Needs {@code publisher-confirm-type: correlated}, {@code publisher-returns: true} and
 * {@code template.mandatory: true}. The message carries the event id as {@code message_id} and the
 * request id of the publishing thread ({@link RequestIdMessages}).
 */
class ConfirmingRabbitTransport implements EventExternalizationTransport, AutoCloseable {

    private final RabbitTemplate rabbit;
    private final EventExternalizationConfiguration configuration;
    private final Duration confirmTimeout;
    // запис у реєстр публікацій (БД) не на потоці підтверджень RabbitMQ і не на таймері orTimeout
    private final ExecutorService completions = Executors.newVirtualThreadPerTaskExecutor();

    ConfirmingRabbitTransport(RabbitTemplate rabbit, EventExternalizationConfiguration configuration,
                              Duration confirmTimeout) {
        this.rabbit = rabbit;
        this.configuration = configuration;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public CompletableFuture<?> externalize(Object event, RoutingTarget target) {
        String id = event instanceof RozkladEvent rozklad ? rozklad.eventId().toString() : UUID.randomUUID().toString();
        CorrelationData correlation = new CorrelationData(id);
        Map<String, Object> headers = configuration.getHeadersFor(event);
        // збій з'єднання кидає виняток тут же: Modulith позначить публікацію FAILED
        rabbit.convertAndSend(target.getTarget(), target.getKey(), event, message -> {
            headers.forEach(message.getMessageProperties()::setHeader);
            // X-Request-Id: MDC приходить сюди з потоку запиту через TaskDecorator (RequestIdPropagationConfig)
            RequestIdMessages.stamp(message.getMessageProperties(), id);
            return message;
        }, correlation);
        return correlation.getFuture()
                .orTimeout(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApplyAsync(confirm -> {
                    if (!confirm.ack()) {
                        throw new IllegalStateException("RabbitMQ did not confirm event " + id + ": "
                                + (confirm.reason() == null ? "no reason" : confirm.reason()));
                    }
                    ReturnedMessage returned = correlation.getReturned();
                    if (returned != null) {
                        throw new IllegalStateException("RabbitMQ returned event %s (%s %d): no queue for %s"
                                .formatted(id, returned.getReplyText(), returned.getReplyCode(),
                                        returned.getRoutingKey()));
                    }
                    return confirm;
                }, completions);
    }

    @Override
    public void close() {
        completions.close();
    }
}
