package ua.vtc.rozklad.importer.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.hibernate.cfg.MappingSettings;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Cross-cutting beans: Kyiv {@link Clock}, JSON conventions (times as {@code HH:mm}), Jackson 3 for
 * Hibernate JSON columns, Ukrainian default locale for validation messages, OpenAPI description.
 */
@Configuration(proxyBeanMethods = false)
public class InfrastructureConfig {

    public static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    public InfrastructureConfig() {
        LocaleContextHolder.setDefaultLocale(Locale.forLanguageTag("uk"));
    }

    @Bean
    Clock clock() {
        return Clock.system(KYIV);
    }

    @Bean
    JsonMapperBuilderCustomizer rozkladJsonConventions() {
        SimpleModule module = new SimpleModule("rozklad-conventions");
        module.addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern("HH:mm")));
        return builder -> builder.addModule(module);
    }

    /** Hibernate would pick Jackson 2 for JSON columns if it were on the classpath. */
    @Bean
    HibernatePropertiesCustomizer jsonFormatMapperCustomizer(JsonMapper jsonMapper) {
        return properties -> properties.put(MappingSettings.JSON_FORMAT_MAPPER,
                new Jackson3JsonFormatMapper(jsonMapper));
    }

    @Bean
    OpenAPI importerOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Розклад ВТФК — importer API").version("v1")
                        .description("Імпорт Excel/DOCX/PDF/фото, AI-розпізнавання, нормалізація"))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
