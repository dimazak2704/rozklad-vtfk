package ua.vtc.rozklad.auth.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * The internal accounts API for core (filter chain 2 of DESIGN §4.1): stateless, bearer JWT issued by this
 * service. Only {@code rozklad-core} may call it: the token must carry {@code SCOPE_accounts.manage}
 * <em>and</em> {@code sub = rozklad-core}, so a notification or importer token gets 403 even if misconfigured.
 * nginx and Caddy also answer 404 for {@code /auth/internal/} (defence in depth).
 */
@Configuration(proxyBeanMethods = false)
public class InternalApiSecurityConfig {

    public static final String CORE_CLIENT = "rozklad-core";
    public static final String SCOPE = "SCOPE_accounts.manage";

    @Bean
    @Order(2)
    SecurityFilterChain internalApiChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/internal/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().access(coreOnly()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    static AuthorizationManager<RequestAuthorizationContext> coreOnly() {
        return (authentication, context) -> {
            var auth = authentication.get();
            boolean allowed = auth != null && auth.isAuthenticated()
                    && auth.getPrincipal() instanceof Jwt jwt
                    && CORE_CLIENT.equals(jwt.getSubject())
                    && auth.getAuthorities().stream().anyMatch(a -> SCOPE.equals(a.getAuthority()));
            return new AuthorizationDecision(allowed);
        };
    }
}
