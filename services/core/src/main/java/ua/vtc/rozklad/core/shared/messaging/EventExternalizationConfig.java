package ua.vtc.rozklad.core.shared.messaging;

import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.contracts.events.OrderPublished;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
import ua.vtc.rozklad.contracts.events.TimetableVersionPublished;

/**
 * Transactional outbox: publish one of the {@link #OUTBOUND} contract events with
 * {@code ApplicationEventPublisher} inside a transaction and Spring Modulith stores it in
 * {@code event_publication}, then sends it after commit to {@code rozklad.events} with the routing
 * key from {@link RozkladEvents}. Events received from the scheduler are never re-externalized.
 */
@Configuration(proxyBeanMethods = false)
public class EventExternalizationConfig {

    /** Contract events produced by core (CONTRACTS.md §7). */
    public static final Set<Class<? extends RozkladEvent>> OUTBOUND = Set.of(
            TimetableVersionPublished.class,
            ScheduleChanged.class,
            GenerationRequested.class,
            GenerationCancelRequested.class,
            SubstitutionConfirmationRequested.class,
            SubstitutionStatusChanged.class,
            SubstitutionTeacherReplied.class,
            AbsenceReported.class,
            OrderPublished.class);

    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> OUTBOUND.contains(event.getClass()))
                .routeAll(event -> RoutingTarget.forTarget(RozkladEvents.EXCHANGE)
                        .andKey(RozkladEvents.routingKey((RozkladEvent) event)))
                .build();
    }
}
