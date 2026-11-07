package ua.vtc.rozklad.core.substitution.fixture;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import ua.vtc.rozklad.contracts.events.RozkladEvents;

/**
 * A temporary queue bound to routing keys of {@code rozklad.events}: proves that an event went
 * through the transactional outbox to RabbitMQ. Messages of other tests are skipped by the filter.
 */
public final class EventProbe {

    private static final long POLL_MILLIS = 250;
    /** The broker drops a forgotten probe queue after two idle minutes. */
    private static final int QUEUE_TTL_MILLIS = 120_000;

    private final RabbitTemplate rabbit;
    private final Queue queue;

    private EventProbe(RabbitTemplate rabbit, Queue queue) {
        this.rabbit = rabbit;
        this.queue = queue;
    }

    public static EventProbe listen(AmqpAdmin admin, RabbitTemplate rabbit, String... routingKeys) {
        // не auto-delete: кожен receive з тайм-аутом відписується, і така черга зникла б після першого
        Queue queue = QueueBuilder.nonDurable("probe." + UUID.randomUUID()).exclusive().expires(QUEUE_TTL_MILLIS)
                .build();
        admin.declareQueue(queue);
        for (String routingKey : routingKeys) {
            admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(RozkladEvents.EXCHANGE))
                    .with(routingKey));
        }
        return new EventProbe(rabbit, queue);
    }

    /** Waits until {@code count} matching events arrived (or the timeout passed) and returns them. */
    public <T> List<T> await(Class<T> type, Predicate<T> filter, int count, Duration timeout) {
        List<T> matched = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (matched.size() < count && System.nanoTime() < deadline) {
            Object message = rabbit.receiveAndConvert(queue.getName(), POLL_MILLIS);
            if (type.isInstance(message) && filter.test(type.cast(message))) {
                matched.add(type.cast(message));
            }
        }
        return matched;
    }
}
