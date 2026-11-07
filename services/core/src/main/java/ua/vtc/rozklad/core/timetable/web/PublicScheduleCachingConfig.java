package ua.vtc.rozklad.core.timetable.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * ETag for the public schedule and ICS (the hottest endpoints): an unchanged answer costs a 304
 * without a body. Together with {@code Cache-Control: no-cache, public} clients always revalidate,
 * so a published change is visible at once.
 */
@Configuration(proxyBeanMethods = false)
class PublicScheduleCachingConfig {

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> publicScheduleEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.setName("publicScheduleEtagFilter");
        registration.addUrlPatterns("/api/public/schedule/*", "/api/public/ics/*");
        return registration;
    }
}
