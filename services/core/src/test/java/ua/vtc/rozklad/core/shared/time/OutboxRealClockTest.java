package ua.vtc.rozklad.core.shared.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.modulith.events.core.DefaultEventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;

class OutboxRealClockTest {

    private static final Instant REAL = Instant.parse("2026-11-14T10:00:00Z");

    private final EventPublicationRepository repository = mock(EventPublicationRepository.class);

    @Test
    void underTheStandTimeTheOutboxKeepsRealTime() {
        Clock stand = StandClock.create(Clock.fixed(REAL, TimeConfig.KYIV), "2026-11-20T10:00", null, false);
        var modulith = new DefaultEventPublicationRegistry(repository, stand);

        Object processed = processor(stand).postProcessAfterInitialization(modulith, "eventPublicationRegistry");

        assertThat(processed).isInstanceOf(DefaultEventPublicationRegistry.class).isNotSameAs(modulith);
        ((DefaultEventPublicationRegistry) processed).deleteCompletedPublicationsOlderThan(Duration.ofDays(7));
        verify(repository).deleteCompletedPublicationsBefore(REAL.minus(Duration.ofDays(7)));
    }

    @Test
    void withRealTimeModulithsRegistryStays() {
        Clock real = Clock.fixed(REAL, TimeConfig.KYIV);
        var modulith = new DefaultEventPublicationRegistry(repository, real);

        assertThat(processor(real).postProcessAfterInitialization(modulith, "eventPublicationRegistry"))
                .isSameAs(modulith);
    }

    @Test
    void otherBeansAreLeftAlone() {
        Clock stand = StandClock.create(Clock.fixed(REAL, TimeConfig.KYIV), "2026-11-20T10:00", null, false);
        Object other = new Object();

        assertThat(processor(stand).postProcessAfterInitialization(other, "other")).isSameAs(other);
    }

    private OutboxRealClock processor(Clock clock) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("clock", clock);
        beans.registerSingleton("repository", repository);
        return new OutboxRealClock(beans.getBeanProvider(Clock.class),
                beans.getBeanProvider(EventPublicationRepository.class));
    }
}
