package ua.vtc.rozklad.core.shared.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;

class EventExternalizationConfigTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T06:00:00Z"), ZoneOffset.UTC);

    private final EventExternalizationConfiguration configuration =
            new EventExternalizationConfig().eventExternalizationConfiguration();

    @Test
    void routesCoreEventsToTopicExchangeWithContractRoutingKey() {
        var event = new ScheduleChanged(EventEnvelope.create(CLOCK), List.of(LocalDate.of(2026, 10, 19)), List.of(),
                List.of(), ScheduleChangeReason.ORDER_PUBLISHED);

        assertThat(configuration.supports(event)).isTrue();
        RoutingTarget target = configuration.determineTarget(event);
        assertThat(target.getTarget()).isEqualTo(RozkladEvents.EXCHANGE);
        assertThat(target.getKey()).isEqualTo("schedule.changed");
    }

    @Test
    void neverReExternalizesEventsReceivedFromScheduler() {
        UUID job = UUID.randomUUID();
        var progress = new GenerationProgress(EventEnvelope.create(CLOCK), job, 2000, -1, -10);
        var completed = new GenerationCompleted(EventEnvelope.create(CLOCK), job, GenerationStatus.SOLVED, 0, -5,
                List.of(), List.of(), null);

        assertThat(configuration.supports(progress)).isFalse();
        assertThat(configuration.supports(completed)).isFalse();
        assertThat(configuration.supports("internal application event")).isFalse();
    }

    @Test
    void everyOutboundTypeIsAContractEventProducedByCore() {
        assertThat(EventExternalizationConfig.OUTBOUND).hasSize(9)
                .allSatisfy(type -> assertThat(RozkladEvents.routingKey(type)).isNotBlank());
    }
}
