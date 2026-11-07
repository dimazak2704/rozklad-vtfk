package ua.vtc.rozklad.core.shared.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of "now". Inject {@link Clock} instead of calling {@code LocalDate.now()}; tests
 * replace it with {@code @TestBean} or {@code @MockitoBean} and {@code Clock.fixed(...)}.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** College time zone: all dates of the domain are local dates in Kyiv. */
    public static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Bean
    Clock clock() {
        return Clock.system(KYIV);
    }
}
