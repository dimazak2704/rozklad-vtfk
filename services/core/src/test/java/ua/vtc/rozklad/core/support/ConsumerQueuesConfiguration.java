package ua.vtc.rozklad.core.support;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Stands in for the consumer queues of scheduler and notification, which a real broker has: core
 * publishes {@code mandatory}, and an event with no queue bound would come back and stay FAILED in the
 * outbox (resent every round). The queue keeps only the newest message; tests read their own probes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ConsumerQueuesConfiguration {

    public static final String SINK = "core-it.consumers";

    @Bean
    Declarables consumerQueuesStandIn(TopicExchange rozkladEventsExchange) {
        Queue sink = QueueBuilder.durable(SINK).maxLength(1).build();
        Binding everything = BindingBuilder.bind(sink).to(rozkladEventsExchange).with("#");
        return new Declarables(sink, everything);
    }
}
