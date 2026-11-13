package ua.vtc.rozklad.core.shared.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.core.EventPublicationRepository;

/**
 * Single source of "now". Inject {@link Clock} instead of calling {@code LocalDate.now()}; tests
 * replace it with {@code @TestBean} or {@code @MockitoBean} and {@code Clock.fixed(...)}. On the
 * development stand {@code app.stand-time.at} shifts it ({@link StandClock}).
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** College time zone: all dates of the domain are local dates in Kyiv. */
    public static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Bean
    Clock clock(Environment environment) {
        return StandClock.create(Clock.system(KYIV), environment.getProperty("app.stand-time.at"),
                environment.getProperty("app.stand-time.anchor"), StandClock.isProduction(environment));
    }

    /** Keeps Modulith's outbox (publication dates, staleness, retention) on real time under the stand time. */
    @Bean
    static BeanPostProcessor outboxRealClock(ObjectProvider<Clock> clock,
                                             ObjectProvider<EventPublicationRepository> repository) {
        return new OutboxRealClock(clock, repository);
    }
}
