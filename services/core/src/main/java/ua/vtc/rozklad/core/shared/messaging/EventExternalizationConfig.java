package ua.vtc.rozklad.core.shared.messaging;

import java.util.Set;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.modulith.events.support.EventExternalizerModuleListener;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.contracts.events.OrderPublished;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionDetailsChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
import ua.vtc.rozklad.contracts.events.TimetableVersionPublished;

/**
 * Transactional outbox: publish one of the {@link #OUTBOUND} contract events with
 * {@code ApplicationEventPublisher} inside a transaction and Spring Modulith stores it in
 * {@code event_publication}, then sends it after commit to {@code rozklad.events} with the routing
 * key from {@link RozkladEvents}. Events received from the scheduler are never re-externalized.
 * <p>
 * Modulith's own RabbitMQ externalizer is excluded ({@code CoreApplication}): it completes a
 * publication as soon as {@code convertAndSend} returns, so a message the broker dropped (unroutable,
 * nacked) was lost. This one uses {@link ConfirmingRabbitTransport}; failed publications are sent
 * again by {@link OutboxResubmitter} while core runs and on restart.
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
            SubstitutionDetailsChanged.class,
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

    @Bean
    ConfirmingRabbitTransport confirmingRabbitTransport(RabbitTemplate rabbitTemplate,
                                                        EventExternalizationConfiguration configuration,
                                                        OutboxProperties properties) {
        return new ConfirmingRabbitTransport(rabbitTemplate, configuration, properties.confirmTimeout());
    }

    /** Same listener class as Modulith's auto-configuration, so stored publications keep their listener id. */
    @Bean
    EventExternalizerModuleListener rabbitEventExternalizer(EventExternalizationConfiguration configuration,
                                                            ConfirmingRabbitTransport transport,
                                                            BeanFactory beanFactory) {
        return new EventExternalizerModuleListener(configuration, transport, beanFactory);
    }
}
