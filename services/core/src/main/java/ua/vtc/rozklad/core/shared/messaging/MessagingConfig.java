package ua.vtc.rozklad.core.shared.messaging;

import java.util.ArrayList;
import java.util.List;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.events.RozkladEvents;

/**
 * RabbitMQ topology of core (ARCHITECTURE.md §5): the shared topic exchange, the dead-letter
 * exchange and core's own queue {@code core.q.scheduler-results} with its DLQ. Listeners consume
 * with {@code @RabbitListener(queues = RozkladEvents.Queues.CORE_SCHEDULER_RESULTS)}.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingConfig {

    static final String CONTRACTS_PACKAGE = "ua.vtc.rozklad.contracts.events";

    @Bean
    TopicExchange rozkladEventsExchange() {
        return ExchangeBuilder.topicExchange(RozkladEvents.EXCHANGE).durable(true).build();
    }

    @Bean
    DirectExchange rozkladDeadLetterExchange() {
        return ExchangeBuilder.directExchange(RozkladEvents.DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    Declarables schedulerResultsQueue(TopicExchange rozkladEventsExchange, DirectExchange rozkladDeadLetterExchange) {
        return consumerQueue(RozkladEvents.Queues.CORE_SCHEDULER_RESULTS, rozkladEventsExchange,
                rozkladDeadLetterExchange);
    }

    /** JSON (Jackson 3, application mapper) for RabbitTemplate and listener containers. */
    @Bean
    MessageConverter rabbitMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper, CONTRACTS_PACKAGE);
    }

    /**
     * Boot's listener factory (retry, requeue and the converter from {@code spring.rabbitmq.listener.*})
     * with {@link RequestIdMessages#listenerMdc()} as the outermost advice.
     */
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        Advice[] configured = factory.getAdviceChain();
        List<Advice> chain = new ArrayList<>();
        chain.add(RequestIdMessages.listenerMdc());
        if (configured != null) {
            chain.addAll(List.of(configured));
        }
        factory.setAdviceChain(chain.toArray(Advice[]::new));
        return factory;
    }

    /** Durable queue bound to its routing keys; rejected messages go to {@code <queue>.dlq}. */
    static Declarables consumerQueue(String name, TopicExchange exchange, DirectExchange deadLetterExchange) {
        Queue queue = QueueBuilder.durable(name)
                .deadLetterExchange(deadLetterExchange.getName())
                .deadLetterRoutingKey(name)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(RozkladEvents.Queues.deadLetterQueue(name)).build();
        List<Declarable> declarables = new ArrayList<>(List.of(queue, deadLetterQueue,
                BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(name)));
        for (String routingKey : RozkladEvents.Queues.bindings(name)) {
            Binding binding = BindingBuilder.bind(queue).to(exchange).with(routingKey);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }
}
