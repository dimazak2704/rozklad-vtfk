package ua.vtc.rozklad.notification.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/** Events through the real exchange and queue {@code notification.q.events}. */
class EventsConsumerIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void admins() {
        owner();
    }

    @Test
    void absenceIsAnnouncedOnceEvenWhenDeliveredTwice() {
        AbsenceReported absence = new AbsenceReported(EventEnvelope.create(Clock.systemUTC()), UUID.randomUUID(),
                CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19),
                List.of(1, 2), AbsenceSource.BOT);
        publish(absence);
        publish(absence);

        await(WAIT, () -> processed(absence.eventId()));
        await(WAIT, () -> queuedFor(OWNER_CHAT) == 1);
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().satisfies(sent -> assertThat(sent.text())
                .isEqualTo("🤒 <b>Відсутність викладача</b>\nКренцін М.Д. — понеділок, 19 жовтня (пари 1, 2)\n"
                        + "Джерело: Telegram-бот\nПідберіть заміни."));
    }

    @Test
    void scheduleChangedDropsCachedSchedules() {
        subscriber(OWNER_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        say(OWNER_CHAT, "📅 Сьогодні");
        say(OWNER_CHAT, "📅 Сьогодні");
        assertThat(core.scheduleCalls()).isEqualTo(1);

        ScheduleChanged changed = new ScheduleChanged(EventEnvelope.create(Clock.systemUTC()),
                List.of(LocalDate.of(2026, 10, 19)), List.of(CoreFixtures.GROUP_4KI1), List.of(),
                ScheduleChangeReason.ORDER_PUBLISHED);
        publish(changed);
        await(WAIT, () -> processed(changed.eventId()));

        say(OWNER_CHAT, "📅 Сьогодні");
        assertThat(core.scheduleCalls()).isEqualTo(2);
    }

    @Test
    void generationResultIsSummarisedForAdmins() {
        GenerationCompleted completed = new GenerationCompleted(EventEnvelope.create(Clock.systemUTC()),
                UUID.randomUUID(), GenerationStatus.SOLVED, 0, -35, List.of(),
                List.of(new GenerationCompleted.ConstraintScore("Вікна в групи", 3, "0hard/-30soft"),
                        new GenerationCompleted.ConstraintScore("Пізні пари", 1, "0hard/-5soft")), null);
        publish(completed);
        await(WAIT, () -> queuedFor(OWNER_CHAT) == 1);
        worker.drain();
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("🧩 <b>Генерацію розкладу завершено</b>")
                .contains("Жорсткі порушення: 0 · штраф м’яких обмежень: 35")
                .contains("• Вікна в групи — 3\n• Пізні пари — 1");
    }

    @Test
    void unreadableMessageGoesToTheDeadLetterQueue() {
        String dlq = RozkladEvents.Queues.deadLetterQueue(RozkladEvents.Queues.NOTIFICATION_EVENTS);
        long before = messagesIn(dlq);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RozkladEvents.EXCHANGE, RozkladEvents.ORDER_PUBLISHED,
                new Message("{not json".getBytes(), properties));
        await(WAIT, () -> messagesIn(dlq) == before + 1);
        rabbit.receive(dlq, 1000);
    }

    private void publish(RozkladEvent event) {
        rabbit.convertAndSend(RozkladEvents.EXCHANGE, RozkladEvents.routingKey(event), event);
    }

    private boolean processed(UUID eventId) {
        Integer count = jdbc.queryForObject("select count(*) from processed_event where event_id = ?", Integer.class,
                eventId);
        return count != null && count == 1;
    }

    private int queuedFor(long chatId) {
        Integer count = jdbc.queryForObject("select count(*) from outbound_message where chat_id = ?", Integer.class,
                chatId);
        return count == null ? 0 : count;
    }

    private long messagesIn(String queue) {
        var info = amqpAdmin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }
}
