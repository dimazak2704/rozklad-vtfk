package ua.vtc.rozklad.notification.events;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.events.RozkladEvents;

/**
 * Events that failed every retry land in {@code notification.q.events.dlq}. Nothing consumes that queue on its
 * own: {@link #check()} (every minute) keeps the gauge {@code notification.events.dead_letters} and logs a
 * warning when the queue grows; {@link #replay()} (an admin, after the cause is fixed) moves the messages back
 * to {@code notification.q.events}. Handlers are idempotent by event id, so a replayed event that was handled
 * after all changes nothing.
 */
@Component
public class DeadLetters {

    static final String QUEUE = RozkladEvents.Queues.NOTIFICATION_EVENTS;
    static final String DEAD_LETTER_QUEUE = RozkladEvents.Queues.deadLetterQueue(QUEUE);
    /**
     * The event's routing key on a replayed message. It is sent straight to the queue (not through the exchange,
     * so other services do not get it again), and the listener takes the event type from this header.
     */
    static final String ORIGINAL_ROUTING_KEY = "x-rozklad-routing-key";

    private static final Logger log = LoggerFactory.getLogger(DeadLetters.class);

    private final AmqpAdmin admin;
    private final RabbitTemplate rabbit;
    private final AtomicLong depth = new AtomicLong();
    private long warnedAt;

    public DeadLetters(AmqpAdmin admin, RabbitTemplate rabbit, MeterRegistry meters) {
        this.admin = admin;
        this.rabbit = rabbit;
        Gauge.builder("notification.events.dead_letters", depth, AtomicLong::get)
                .description("Events in " + DEAD_LETTER_QUEUE + " (failed every retry, not handled)")
                .register(meters);
    }

    public static String deadLetterQueue() {
        return DEAD_LETTER_QUEUE;
    }

    /** @return messages in the dead-letter queue, or -1 when RabbitMQ cannot be asked (the gauge keeps its value) */
    public synchronized long check() {
        long count;
        try {
            QueueInformation info = admin.getQueueInfo(DEAD_LETTER_QUEUE);
            count = info == null ? 0 : info.getMessageCount();
        } catch (AmqpException e) {
            log.debug("Cannot read {}: {}", DEAD_LETTER_QUEUE, e.getMessage());
            return -1;
        }
        depth.set(count);
        if (count > warnedAt) {
            log.warn("{} event(s) in {} failed every retry and were not handled (orders, substitutions or admin "
                    + "notices may be missing). Fix the cause, then replay: POST /api/notify/events/dead-letters/replay",
                    count, DEAD_LETTER_QUEUE);
        }
        warnedAt = count;
        return count;
    }

    /**
     * Moves the messages that are in the dead-letter queue now back to the consumer, each once: one that fails
     * again returns to the end of the queue and is not taken a second time by this call.
     *
     * @return number of messages moved, -1 when RabbitMQ cannot be asked
     */
    public synchronized int replay() {
        long count = check();
        if (count <= 0) {
            return (int) count;
        }
        int moved = 0;
        for (long i = 0; i < count && Boolean.TRUE.equals(rabbit.execute(DeadLetters::moveOne)); i++) {
            moved++;
        }
        log.info("Replayed {} dead-lettered event(s) to {}", moved, QUEUE);
        check();
        return moved;
    }

    /** Takes one message off the DLQ and republishes it; it is acknowledged only after the publish. */
    private static boolean moveOne(Channel channel) throws IOException {
        GetResponse response = channel.basicGet(DEAD_LETTER_QUEUE, false);
        if (response == null) {
            return false;
        }
        AMQP.BasicProperties properties = response.getProps();
        Map<String, Object> headers = properties.getHeaders() == null ? new HashMap<>()
                : new HashMap<>(properties.getHeaders());
        String routingKey = originalRoutingKey(headers);
        if (routingKey != null) {
            headers.put(ORIGINAL_ROUTING_KEY, routingKey);
        }
        channel.basicPublish("", QUEUE, properties.builder().headers(headers).build(), response.getBody());
        channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
        return true;
    }

    /** The routing key the event was published with: a previous replay's header, else the first death record. */
    static String originalRoutingKey(Map<String, Object> headers) {
        Object replayed = headers.get(ORIGINAL_ROUTING_KEY);
        if (replayed != null) {
            return replayed.toString();
        }
        if (headers.get("x-death") instanceof List<?> deaths && !deaths.isEmpty()
                && deaths.getLast() instanceof Map<?, ?> first
                && first.get("routing-keys") instanceof List<?> keys && !keys.isEmpty()) {
            return keys.getFirst().toString();
        }
        return null;
    }
}
