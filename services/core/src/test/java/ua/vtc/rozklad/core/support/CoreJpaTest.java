package ua.vtc.rozklad.core.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import ua.vtc.rozklad.core.shared.persistence.JpaConfig;
import ua.vtc.rozklad.core.shared.time.TimeConfig;
import ua.vtc.rozklad.core.shared.web.JacksonConfig;

/**
 * JPA slice on a real PostgreSQL (Flyway migrations + {@code ddl-auto=validate}) with the shared
 * persistence setup: auditing from the {@code Clock}, Jackson 3 for JSON columns.
 * Name the test class {@code *IT} (it needs Docker).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataJpaTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({TestcontainersConfiguration.class, JpaConfig.class, TimeConfig.class, JacksonConfig.class})
public @interface CoreJpaTest {
}
