package ua.vtc.rozklad.core.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ua.vtc.rozklad.core.shared.time.TimeConfig;

/**
 * A clock that integration tests can stop at a chosen moment without a context of their own: it
 * follows the system clock (Kyiv) until {@link #stopAt} and again after {@link #reset}. A test that
 * stops it resets it in {@code @AfterEach}.
 */
public final class TestClock extends Clock {

    private final AtomicReference<Instant> stopped = new AtomicReference<>();

    public void stopAt(Instant instant) {
        stopped.set(instant);
    }

    public void reset() {
        stopped.set(null);
    }

    @Override
    public ZoneId getZone() {
        return TimeConfig.KYIV;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        Instant at = stopped.get();
        return at != null ? at : Instant.now();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }
}
