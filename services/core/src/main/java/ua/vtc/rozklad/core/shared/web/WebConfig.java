package ua.vtc.rozklad.core.shared.web;

import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ua.vtc.rozklad.core.shared.security.CurrentUserArgumentResolver;

/**
 * MVC setup: {@code CurrentUser} controller parameters and Ukrainian as the default locale (also
 * for validation outside HTTP requests, e.g. in message listeners).
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
}
