package ua.vtc.rozklad.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.redis.testcontainers.RedisContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.support.TestcontainersConfiguration;

/**
 * The outbox delivers while core keeps running: an event published while RabbitMQ is down, or that the
 * broker returns because no consumer queue is bound yet, is FAILED (not COMPLETED) and is sent again by
 * the resubmission job once the broker can take it, without a restart. Own context: it stops its broker.
 */
@SpringBootTest(properties = {
        "app.outbox.resubmit-interval=1s",
        "app.outbox.resubmit-min-age=0s",
        "app.outbox.confirm-timeout=5s"})
@Import(OutboxRedeliveryIT.Infrastructure.class)
class OutboxRedeliveryIT {

    private static final Duration DELIVERY = Duration.ofSeconds(90);

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitMQContainer rabbit;

    @Autowired
    private Clock clock;

    @Test
    void eventPublishedWhileTheBrokerIsDownIsDeliveredOnceItIsBack() {
        Queue consumer = durableQueueFor(RozkladEvents.SCHEDULE_CHANGED, "outbox-it.schedule");
        var event = new ScheduleChanged(EventEnvelope.create(clock), List.of(LocalDate.of(2026, 10, 19)),
                List.of(UUID.randomUUID()), List.of(), ScheduleChangeReason.LESSONS_EDITED);
        rabbit.getDockerClient().stopContainerCmd(rabbit.getContainerId()).exec();
        try {
            transaction.executeWithoutResult(status -> events.publishEvent(event));

            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(status(event.eventId())).isEqualTo("FAILED"));
        } finally {
            rabbit.getDockerClient().startContainerCmd(rabbit.getContainerId()).exec();
        }

        await().atMost(DELIVERY).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .untilAsserted(() -> assertThat(rabbitTemplate.receiveAndConvert(consumer.getName(), 1_000))
                        .isEqualTo(event));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(event.eventId())).isEqualTo("COMPLETED"));
    }

    @Test
    void eventWithoutAConsumerQueueIsReturnedAndSentAgainWhenTheQueueAppears() {
        var cancel = new GenerationCancelRequested(EventEnvelope.create(clock), UUID.randomUUID());

        transaction.executeWithoutResult(status -> events.publishEvent(cancel));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(status(cancel.eventId())).isEqualTo("FAILED"));
        Queue scheduler = durableQueueFor(RozkladEvents.GENERATION_CANCEL, "outbox-it.generation-cancel");
        await().atMost(DELIVERY).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .untilAsserted(() -> assertThat(rabbitTemplate.receiveAndConvert(scheduler.getName(), 1_000))
                        .isEqualTo(cancel));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(cancel.eventId())).isEqualTo("COMPLETED"));
    }

    private String status(UUID eventId) {
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM event_publication WHERE serialized_event LIKE ?", String.class,
                "%" + eventId + "%");
        return statuses.size() == 1 ? statuses.getFirst() : String.valueOf(statuses);
    }

    /** Durable, so it survives the broker restart, like the real consumer queues. */
    private Queue durableQueueFor(String routingKey, String name) {
        Queue queue = QueueBuilder.durable(name).build();
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(RozkladEvents.EXCHANGE))
                .with(routingKey));
        amqpAdmin.purgeQueue(name, false);
        return queue;
    }

    /** Own containers; RabbitMQ on a fixed host port, so a stopped and started broker is found again. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {

        private static final int AMQP_PORT = 5672;

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES);
        }

        @Bean
        @ServiceConnection
        RedisContainer redisContainer() {
            return new RedisContainer(TestcontainersConfiguration.REDIS);
        }

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitContainer() {
            int port = freePort();
            RabbitMQContainer container = new RabbitMQContainer(TestcontainersConfiguration.RABBITMQ);
            // AMQP на сталому порту, решта портів (керування) — як завжди, на випадкових
            return container.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    container.getExposedPorts().stream()
                            .map(exposed -> new PortBinding(exposed == AMQP_PORT ? Ports.Binding.bindPort(port)
                                    : Ports.Binding.empty(), new ExposedPort(exposed)))
                            .toList()));
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
