package ua.vtc.rozklad.auth.session;

import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Sign-in sessions live in PostgreSQL (Spring Session JDBC), so a restart or deploy logs nobody out.
 * The {@link SessionRegistry} that Spring AS uses for {@code sid} and OIDC logout reads them through the
 * principal-name index; {@code SessionRegistryImpl} would need session events that JDBC sessions never send.
 */
@Configuration(proxyBeanMethods = false)
public class SessionConfig {

    /** Spring Session's filter runs at {@code Integer.MIN_VALUE + 50}, Spring Security at -100. */
    static final int ABSOLUTE_TIMEOUT_ORDER = -200;

    @Bean
    SessionRegistry sessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions) {
        return registry(sessions);
    }

    @Bean
    FilterRegistrationBean<AbsoluteSessionTimeoutFilter> absoluteSessionTimeoutFilter(AuthServerProperties properties,
            Clock clock) {
        var registration = new FilterRegistrationBean<>(
                new AbsoluteSessionTimeoutFilter(properties.session().maxLifetime(), clock));
        registration.setOrder(ABSOLUTE_TIMEOUT_ORDER);
        return registration;
    }

    private static <S extends Session> SessionRegistry registry(FindByIndexNameSessionRepository<S> sessions) {
        return new SpringSessionBackedSessionRegistry<>(sessions);
    }
}
