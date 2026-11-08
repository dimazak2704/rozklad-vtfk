package ua.vtc.rozklad.core.shared.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.YearMonthDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * JSON conventions of CONTRACTS.md: times are written as {@code HH:mm}; reading accepts ISO too.
 * Strings with a NUL character are rejected ({@link NulFreeStringDeserializer}), dates outside
 * {@link SupportedDates} too, and a fractional number for an integer field ({@code "pair": 1.2}) is
 * a 400 instead of being silently truncated.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer rozkladJsonConventions() {
        SimpleModule module = new SimpleModule("rozklad-conventions");
        module.addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern("HH:mm")));
        module.addDeserializer(String.class, new NulFreeStringDeserializer());
        module.addDeserializer(LocalDate.class,
                new SupportedDateDeserializer<>(LocalDate.class, LocalDateDeserializer.INSTANCE));
        module.addDeserializer(YearMonth.class,
                new SupportedDateDeserializer<>(YearMonth.class, YearMonthDeserializer.INSTANCE));
        return builder -> builder.addModule(module).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
