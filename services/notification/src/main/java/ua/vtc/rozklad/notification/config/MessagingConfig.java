package ua.vtc.rozklad.notification.config;

import java.util.ArrayList;
import java.util.List;
import org.aopalliance.aop.Advice;
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
 * RabbitMQ topology of notification: queue {@code notification.q.events} bound to every core
 * event it reacts to (see {@link RozkladEvents.Queues#bindings}) with its DLQ.
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
    Declarables notificationEventsQueue(TopicExchange rozkladEventsExchange,
                                        DirectExchange rozkladDeadLetterExchange) {
        String name = RozkladEvents.Queues.NOTIFICATION_EVENTS;
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

    /**
     * Boot's listener factory (auto-startup, retry, requeue and the converter from
     * {@code spring.rabbitmq.listener.*}) with {@link RequestIdMessages#listenerMdc()} as the outermost advice.
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

    /** JSON (Jackson 3, application mapper) for RabbitTemplate and listener containers. */
    @Bean
    MessageConverter rabbitMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper, "ua.vtc.rozklad.contracts.events");
    }
}
