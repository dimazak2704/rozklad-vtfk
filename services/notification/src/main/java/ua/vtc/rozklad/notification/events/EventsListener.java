package ua.vtc.rozklad.notification.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.RozkladEvents;

/**
 * Consumer of {@code notification.q.events}. The payload type comes from the routing key, so a
 * message is read correctly even without a type header. Unreadable messages go to the DLQ;
 * handler failures are retried (3 attempts) and then dead-lettered. A message replayed from the DLQ
 * ({@link DeadLetters}) comes straight to the queue and carries its routing key in a header.
 */
@Component
public class EventsListener {

    private static final Logger log = LoggerFactory.getLogger(EventsListener.class);

    private final JsonMapper json;
    private final EventHandlers handlers;

    public EventsListener(JsonMapper json, EventHandlers handlers) {
        this.json = json;
        this.handlers = handlers;
    }

    @RabbitListener(queues = RozkladEvents.Queues.NOTIFICATION_EVENTS)
    public void onMessage(Message message) {
        Object replayed = message.getMessageProperties().getHeader(DeadLetters.ORIGINAL_ROUTING_KEY);
        String routingKey = replayed != null ? replayed.toString()
                : message.getMessageProperties().getReceivedRoutingKey();
        RozkladEvent event;
        try {
            event = json.readValue(message.getBody(), RozkladEvents.eventType(routingKey));
        } catch (IllegalArgumentException | JacksonException e) {
            log.error("Unreadable event with routing key {}: {}", routingKey, e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Unreadable event " + routingKey, e);
        }
        log.debug("Received {} {}", routingKey, event.eventId());
        handlers.handle(event);
    }
}
