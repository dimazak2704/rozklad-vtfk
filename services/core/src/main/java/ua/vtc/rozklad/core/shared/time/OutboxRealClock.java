package ua.vtc.rozklad.core.shared.time;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.modulith.events.core.DefaultEventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;

/**
 * Spring Modulith stamps outbox publications with the {@link Clock} bean and judges staleness and
 * retention by it. Under the stand time that would date publications days ahead, so a restart on
 * real time would leave them "fresh" for days. This swaps the registry for one on the real clock;
 * with the real clock (production, tests) the registry stays as Modulith built it.
 */
final class OutboxRealClock implements BeanPostProcessor {

    private final ObjectProvider<Clock> clock;
    private final ObjectProvider<EventPublicationRepository> repository;

    OutboxRealClock(ObjectProvider<Clock> clock, ObjectProvider<EventPublicationRepository> repository) {
        this.clock = clock;
        this.repository = repository;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DefaultEventPublicationRegistry && StandClock.isShifted(clock.getObject())) {
            return new DefaultEventPublicationRegistry(repository.getObject(), StandClock.real(clock.getObject()));
        }
        return bean;
    }
}
