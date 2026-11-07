package ua.vtc.rozklad.contracts.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.GenerationStatus;

class RozkladEventsTest {

    @Test
    void everyEventTypeHasRoutingKeyAndBack() {
        for (Class<?> type : RozkladEvent.class.getPermittedSubclasses()) {
            @SuppressWarnings("unchecked")
            var eventType = (Class<? extends RozkladEvent>) type;
            String key = RozkladEvents.routingKey(eventType);

            assertThat(RozkladEvents.eventType(key)).isEqualTo(eventType);
        }
        assertThat(RozkladEvents.routingKeys()).hasSize(RozkladEvent.class.getPermittedSubclasses().length);
    }

    @Test
    void routingKeyOfInstanceMatchesItsType() {
        for (RozkladEvent event : EventSamples.all()) {
            assertThat(RozkladEvents.routingKey(event)).isEqualTo(RozkladEvents.routingKey(event.getClass()));
        }
    }

    @Test
    void everyEventIsConsumedByAtLeastOneQueueAndBindingsAreKnownKeys() {
        Set<String> bound = RozkladEvents.Queues.all().values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        assertThat(bound).containsExactlyInAnyOrderElementsOf(RozkladEvents.routingKeys().values());
    }

    @Test
    void deadLetterQueueFollowsNamingConvention() {
        assertThat(RozkladEvents.Queues.deadLetterQueue(RozkladEvents.Queues.NOTIFICATION_EVENTS))
                .isEqualTo("notification.q.events.dlq");
        assertThat(RozkladEvents.Queues.bindings(RozkladEvents.Queues.SCHEDULER_GENERATION))
                .containsExactly(RozkladEvents.GENERATION_REQUESTED, RozkladEvents.GENERATION_CANCEL);
    }

    @Test
    void unknownRoutingKeyIsRejected() {
        assertThatThrownBy(() -> RozkladEvents.eventType("order.deleted"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedEventRequiresTerminalStatus() {
        assertThatThrownBy(() -> new GenerationCompleted(UUID.randomUUID(), Instant.EPOCH, 1, UUID.randomUUID(),
                GenerationStatus.RUNNING, 0, 0, List.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envelopeRejectsInvalidVersion() {
        assertThatThrownBy(() -> new EventEnvelope(UUID.randomUUID(), Instant.EPOCH, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
