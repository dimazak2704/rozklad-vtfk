package ua.vtc.rozklad.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;

/** Transactional outbox: contract events published by core reach RabbitMQ after commit. */
@CoreIntegrationTest
class EventExternalizationIT {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private Clock clock;

    @Test
    void coreEventIsSentToTopicExchangeAfterCommit() {
        Queue probe = probeQueueFor(RozkladEvents.SCHEDULE_CHANGED);
        var event = new ScheduleChanged(EventEnvelope.create(clock), List.of(LocalDate.of(2026, 10, 19)),
                List.of(UUID.randomUUID()), List.of(), ScheduleChangeReason.LESSONS_EDITED);

        transaction.executeWithoutResult(status -> events.publishEvent(event));

        Object received = rabbitTemplate.receiveAndConvert(probe.getName(), 15_000);
        assertThat(received).isEqualTo(event);
    }

    @Test
    void eventOfRolledBackTransactionIsNotSent() {
        Queue probe = probeQueueFor(RozkladEvents.SCHEDULE_CHANGED);
        var event = new ScheduleChanged(EventEnvelope.create(clock), List.of(), List.of(), List.of(),
                ScheduleChangeReason.CALENDAR_CHANGED);

        transaction.executeWithoutResult(status -> {
            events.publishEvent(event);
            status.setRollbackOnly();
        });

        assertThat(rabbitTemplate.receiveAndConvert(probe.getName(), 1_500)).isNull();
    }

    @Test
    void schedulerEventsAreNotSentBackToTheBroker() {
        Queue probe = probeQueueFor(RozkladEvents.GENERATION_PROGRESS);
        var progress = new GenerationProgress(EventEnvelope.create(clock), UUID.randomUUID(), 2_000, 0, -12);

        transaction.executeWithoutResult(status -> events.publishEvent(progress));

        assertThat(rabbitTemplate.receiveAndConvert(probe.getName(), 1_500)).isNull();
    }

    private Queue probeQueueFor(String routingKey) {
        Queue probe = new AnonymousQueue();
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(RozkladEvents.EXCHANGE))
                .with(routingKey));
        return probe;
    }
}
