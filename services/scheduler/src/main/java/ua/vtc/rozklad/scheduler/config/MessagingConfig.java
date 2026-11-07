package ua.vtc.rozklad.scheduler.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.events.RozkladEvents;

/**
 * RabbitMQ topology of the scheduler: queue {@code scheduler.q.generation} (generation.requested,
 * generation.cancel) with its DLQ. Results are published with {@code RabbitTemplate} to
 * {@link RozkladEvents#EXCHANGE} after the local transaction commits.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingConfig {

    @Bean
    TopicExchange rozkladEventsExchange() {
        return ExchangeBuilder.topicExchange(RozkladEvents.EXCHANGE).durable(true).build();
    }

    @Bean
    DirectExchange rozkladDeadLetterExchange() {
        return ExchangeBuilder.directExchange(RozkladEvents.DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    Declarables generationQueue(TopicExchange rozkladEventsExchange, DirectExchange rozkladDeadLetterExchange) {
        String name = RozkladEvents.Queues.SCHEDULER_GENERATION;
        Queue queue = QueueBuilder.durable(name)
                .deadLetterExchange(rozkladDeadLetterExchange.getName())
                .deadLetterRoutingKey(name)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(RozkladEvents.Queues.deadLetterQueue(name)).build();
        List<Declarable> declarables = new ArrayList<>(List.of(queue, deadLetterQueue,
                BindingBuilder.bind(deadLetterQueue).to(rozkladDeadLetterExchange).with(name)));
        RozkladEvents.Queues.bindings(name).forEach(routingKey ->
                declarables.add(BindingBuilder.bind(queue).to(rozkladEventsExchange).with(routingKey)));
        return new Declarables(declarables);
    }

    /** JSON (Jackson 3, application mapper) for RabbitTemplate and listener containers. */
    @Bean
    MessageConverter rabbitMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper, "ua.vtc.rozklad.contracts.events");
    }
}
