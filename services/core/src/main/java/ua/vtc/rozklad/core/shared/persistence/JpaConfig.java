package ua.vtc.rozklad.core.shared.persistence;

import java.time.Clock;
import java.util.Optional;
import org.hibernate.cfg.MappingSettings;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import tools.jackson.databind.json.JsonMapper;

/**
 * JPA infrastructure shared by all modules.
 *
 * <p>Hibernate prefers Jackson 2 for JSON columns whenever it is on the classpath (here it comes
 * with keycloak-admin-client), so the Jackson 3 mapper of the application is set explicitly.
 * Slice tests ({@code @DataJpaTest}) must {@code @Import(JpaConfig.class)}.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }

    @Bean
    HibernatePropertiesCustomizer jsonFormatMapperCustomizer(JsonMapper jsonMapper) {
        return properties -> properties.put(MappingSettings.JSON_FORMAT_MAPPER,
                new Jackson3JsonFormatMapper(jsonMapper));
    }
}
