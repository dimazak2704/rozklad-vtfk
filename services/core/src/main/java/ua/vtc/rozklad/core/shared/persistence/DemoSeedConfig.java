package ua.vtc.rozklad.core.shared.persistence;

import java.util.Arrays;
import java.util.stream.Stream;
import org.flywaydb.core.api.Location;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Demo data (DOMAIN §12) is an extra Flyway location that exists only with the Spring profile
 * {@value #PROFILE}: semesters 2026/2027, departments, specialties, cycle commissions and calendar
 * periods. Tests and production run without it, so their databases stay clean.
 *
 * <p>A database migrated once with the profile keeps the {@code V9_xxx} rows in its history;
 * always start it with the profile again (Flyway would report the demo migrations as missing).
 */
@Configuration(proxyBeanMethods = false)
@Profile(DemoSeedConfig.PROFILE)
public class DemoSeedConfig {

    public static final String PROFILE = "demo";
    public static final String LOCATION = "classpath:db/demo";

    @Bean
    FlywayConfigurationCustomizer demoSeedLocation() {
        return configuration -> {
            Location demo = new Location(LOCATION);
            Location[] current = configuration.getLocations();
            if (Arrays.asList(current).contains(demo)) {
                return;
            }
            configuration.locations(Stream.concat(Arrays.stream(current), Stream.of(demo)).toArray(Location[]::new));
        };
    }
}
