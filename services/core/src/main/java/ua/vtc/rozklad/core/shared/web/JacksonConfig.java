package ua.vtc.rozklad.core.shared.web;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

/** JSON conventions of CONTRACTS.md: times are written as {@code HH:mm}; reading accepts ISO too. */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer rozkladJsonConventions() {
        SimpleModule module = new SimpleModule("rozklad-conventions");
        module.addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern("HH:mm")));
        return builder -> builder.addModule(module);
    }
}
