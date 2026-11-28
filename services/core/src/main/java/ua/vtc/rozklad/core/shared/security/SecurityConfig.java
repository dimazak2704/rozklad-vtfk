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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless resource server (ARCHITECTURE.md §6). Path rules: public API and health are open,
 * {@code /api/admin/**} → ADMIN (import apply also SERVICE_IMPORTER), {@code /api/me/**} → TEACHER or
 * ADMIN, SSE → ADMIN or TEACHER. The internal API is split per service account: the bot's endpoints
 * (teacher replies, absences, teachers, mail recipients) → SERVICE_NOTIFICATION, the catalog snapshot →
 * SERVICE_IMPORTER, any other {@code /api/internal/**} is denied, as is everything else. The plain
 * SERVICE role opens nothing here, so a leaked importer token cannot answer for a teacher.
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
                        .requestMatchers("/api/admin/import/**").hasAnyRole(Roles.ADMIN, Roles.SERVICE_IMPORTER)
                        .requestMatchers("/api/admin/**").hasRole(Roles.ADMIN)
                        .requestMatchers("/api/me", "/api/me/**").hasAnyRole(Roles.TEACHER, Roles.ADMIN)
                        .requestMatchers("/api/internal/changes/**", "/api/internal/absences",
                                "/api/internal/absences/**", "/api/internal/teachers/**",
                                "/api/internal/mail-recipients")
                        .hasRole(Roles.SERVICE_NOTIFICATION)
                        .requestMatchers("/api/internal/catalog/**").hasRole(Roles.SERVICE_IMPORTER)
                        .requestMatchers("/api/internal/**").denyAll()
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

    /**
     * JWKS from {@code AUTH_JWKS_URI} (cached; unknown key ids refetch it at most twice per 30 s), issuer and
     * audience from {@link AuthProperties} (no discovery call).
     */
    @Bean
    JwtDecoder jwtDecoder(AuthProperties auth) {
        return JwksDecoder.create(auth.jwksUri(), auth.tokenValidator());
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(TokenRoles::authorities);
        return converter;
    }
}
