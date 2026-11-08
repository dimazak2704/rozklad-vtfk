package ua.vtc.rozklad.core.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

/** The demo seed is an extra Flyway location of the {@code demo} profile only. */
class DemoSeedConfigTest {

    @Test
    void addsTheDemoLocationOnceNextToTheSchemaMigrations() {
        FluentConfiguration configuration = Flyway.configure().locations("classpath:db/migration");

        new DemoSeedConfig().demoSeedLocation().customize(configuration);
        new DemoSeedConfig().demoSeedLocation().customize(configuration);

        // порядок локацій неважливий: Flyway впорядковує міграції за версією
        assertThat(configuration.getLocations()).extracting(Object::toString)
                .containsExactlyInAnyOrder("classpath:db/migration", "classpath:db/demo");
    }

    @Test
    void isActiveOnlyWithTheDemoProfile() {
        assertThat(DemoSeedConfig.class.getAnnotation(Profile.class).value()).containsExactly("demo");
    }
}
