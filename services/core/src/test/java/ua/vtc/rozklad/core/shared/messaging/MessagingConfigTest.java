package ua.vtc.rozklad.core.shared.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import ua.vtc.rozklad.contracts.events.RozkladEvents;

class MessagingConfigTest {

    private final MessagingConfig config = new MessagingConfig();

    @Test
    void consumerQueueDeadLettersIntoItsOwnDlq() {
        Declarables declarables = config.schedulerResultsQueue(config.rozkladEventsExchange(),
                config.rozkladDeadLetterExchange());

        Queue queue = declarables.getDeclarablesByType(Queue.class).stream()
                .filter(q -> q.getName().equals(RozkladEvents.Queues.CORE_SCHEDULER_RESULTS)).findFirst().orElseThrow();
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", RozkladEvents.DEAD_LETTER_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RozkladEvents.Queues.CORE_SCHEDULER_RESULTS);
        assertThat(declarables.getDeclarablesByType(Queue.class)).extracting(Queue::getName)
                .contains("core.q.scheduler-results.dlq");
    }

    @Test
    void consumerQueueIsBoundToSchedulerResultKeys() {
        List<Binding> bindings = config.schedulerResultsQueue(config.rozkladEventsExchange(),
                config.rozkladDeadLetterExchange()).getDeclarablesByType(Binding.class);

        assertThat(bindings).filteredOn(binding -> binding.getExchange().equals(RozkladEvents.EXCHANGE))
                .extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrder(RozkladEvents.GENERATION_PROGRESS, RozkladEvents.GENERATION_COMPLETED);
        assertThat(bindings).filteredOn(binding -> binding.getExchange().equals(RozkladEvents.DEAD_LETTER_EXCHANGE))
                .extracting(Binding::getDestination).containsExactly("core.q.scheduler-results.dlq");
    }
}
