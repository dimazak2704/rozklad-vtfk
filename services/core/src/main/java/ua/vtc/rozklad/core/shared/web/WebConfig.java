package ua.vtc.rozklad.core.shared.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ua.vtc.rozklad.core.shared.security.CurrentUserArgumentResolver;

/**
 * MVC setup: {@code CurrentUser} controller parameters, date parameters limited to
 * {@link SupportedDates} and Ukrainian as the default locale (also for validation outside HTTP
 * requests, e.g. in message listeners).
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    public static final Locale UKRAINIAN = Locale.forLanguageTag("uk");

    public WebConfig() {
        LocaleContextHolder.setDefaultLocale(UKRAINIAN);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        // параметри без @DateTimeFormat — ISO, з тим самим обмеженням років; реєструється першим,
        // бо пізніше зареєстрований конвертер перевіряється раніше, а анотований має переважати
        registry.addFormatterForFieldType(LocalDate.class, new Formatter<LocalDate>() {
            @Override
            public LocalDate parse(String text, Locale locale) {
                return SupportedDates.require(LocalDate.parse(text));
            }

            @Override
            public String print(LocalDate value, Locale locale) {
                return value.toString();
            }
        });
        registry.addFormatterForFieldAnnotation(new SupportedDateFormatterFactory());
    }
}
