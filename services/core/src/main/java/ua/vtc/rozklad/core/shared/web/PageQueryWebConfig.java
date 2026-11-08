package ua.vtc.rozklad.core.shared.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link PageQueryArgumentResolver} for {@link PageQuery} controller parameters. */
@Configuration(proxyBeanMethods = false)
public class PageQueryWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageQueryArgumentResolver());
    }
}
