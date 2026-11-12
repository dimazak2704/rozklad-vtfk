package ua.vtc.rozklad.notification.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/**
 * Events that failed all retries sit in {@code notification.q.events.dlq}: their number is a metric and a
 * warning, and an admin can send them back to the consumer once the cause is fixed.
 */
class DeadLettersIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String DLQ = RozkladEvents.Queues.deadLetterQueue(RozkladEvents.Queues.NOTIFICATION_EVENTS);

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private DeadLetters deadLetters;
    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void emptyDeadLetters() {
        amqpAdmin.purgeQueue(DLQ, false);
        owner();
    }

    @Test
    void deadLetteredEventIsCountedAndReplayedByAnAdmin() throws Exception {
        AbsenceReported absence = new AbsenceReported(EventEnvelope.create(Clock.systemUTC()), UUID.randomUUID(),
                CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19),
                List.of(1, 2), AbsenceSource.BOT);
        deadLetter(json.writeValueAsBytes(absence), RozkladEvents.routingKey(absence));

        assertThat(deadLetters.check()).isEqualTo(1);
        assertThat(meters.get("notification.events.dead_letters").gauge().value()).isEqualTo(1.0);
        mockMvc.perform(get("/api/notify/events/dead-letters").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue").value(DLQ))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(post("/api/notify/events/dead-letters/replay").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(1));
        await(WAIT, () -> processed(absence.eventId()));
        worker.drain();
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("🤒 <b>Відсутність викладача</b>");
        assertThat(deadLetters.check()).isZero();
        assertThat(meters.get("notification.events.dead_letters").gauge().value()).isZero();
    }

    /** A poison message goes back to the DLQ; one replay takes each message once, so it never loops. */
    @Test
    void replayTakesEachMessageOnceEvenIfItFailsAgain() {
        deadLetter("{not json".getBytes(), RozkladEvents.ORDER_PUBLISHED);

        assertThat(deadLetters.replay()).isEqualTo(1);
        await(WAIT, () -> deadLetters.check() == 1);
        assertThat(deadLetters.replay()).isEqualTo(1);
        await(WAIT, () -> deadLetters.check() == 1);
    }

    @Test
    void onlyAdminsSeeAndReplayDeadLetters() throws Exception {
        mockMvc.perform(post("/api/notify/events/dead-letters/replay").with(teacher("t-1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notify/events/dead-letters")).andExpect(status().isUnauthorized());
    }

    /** What the dead-letter exchange leaves after the retries: the payload and the original routing key. */
    private void deadLetter(byte[] body, String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("x-death", List.of(Map.of("queue", RozkladEvents.Queues.NOTIFICATION_EVENTS,
                "reason", "rejected", "count", 1L, "exchange", RozkladEvents.EXCHANGE,
                "routing-keys", List.of(routingKey))));
        rabbit.send(RozkladEvents.DEAD_LETTER_EXCHANGE, RozkladEvents.Queues.NOTIFICATION_EVENTS,
                new Message(body, properties));
        await(WAIT, () -> {
            var info = amqpAdmin.getQueueInfo(DLQ);
            return info != null && info.getMessageCount() == 1;
        });
    }

    private boolean processed(UUID eventId) {
        Integer count = jdbc.queryForObject("select count(*) from processed_event where event_id = ?", Integer.class,
                eventId);
        return count != null && count == 1;
    }
}
