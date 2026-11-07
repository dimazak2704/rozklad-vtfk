package ua.vtc.rozklad.core.shared.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless resource server (ARCHITECTURE.md §6). Path rules: public API and health are open,
 * {@code /api/admin/**} → ADMIN (import apply also SERVICE), {@code /api/me/**} → TEACHER or
 * ADMIN, {@code /api/internal/**} → SERVICE, SSE → ADMIN or TEACHER; everything else is denied.
 * Ownership checks (a teacher touches only own data) live in the services.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ProblemDetailSecurityHandlers problemHandlers,
                                               JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/admin/import/**").hasAnyRole(Roles.ADMIN, Roles.SERVICE)
                        .requestMatchers("/api/admin/**").hasRole(Roles.ADMIN)
                        .requestMatchers("/api/me", "/api/me/**").hasAnyRole(Roles.TEACHER, Roles.ADMIN)
                        .requestMatchers("/api/internal/**").hasRole(Roles.SERVICE)
                        .requestMatchers("/api/events/stream").hasAnyRole(Roles.ADMIN, Roles.TEACHER)
                        .requestMatchers("/actuator/**").hasRole(Roles.ADMIN)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers));
        return http.build();
    }

    /** JWKS from {@code KEYCLOAK_JWKS_URI}, issuer checked against {@code KEYCLOAK_ISSUER} (no discovery call). */
    @Bean
    JwtDecoder jwtDecoder(KeycloakProperties keycloak) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(keycloak.jwksUri().toString()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(keycloak.issuer()));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }
}
