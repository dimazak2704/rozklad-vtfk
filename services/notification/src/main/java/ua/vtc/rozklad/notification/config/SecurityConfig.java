package ua.vtc.rozklad.notification.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
 * Stateless resource server (CONTRACTS §6):
 * <ul>
 *   <li>{@code GET /api/documents/orders/{id}.{docx|pdf|png}} — public; the service must serve
 *       only PUBLISHED orders;</li>
 *   <li>{@code POST /api/documents/orders/render} — ADMIN;</li>
 *   <li>{@code POST /api/notify/bindings/codes} — ADMIN or TEACHER (a teacher only for own code);</li>
 *   <li>{@code POST /api/notify/telegram/webhook} — open, the Telegram secret header is checked in
 *       the controller;</li>
 *   <li>other {@code /api/notify/**} — ADMIN; everything else is denied.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    public static final String ADMIN = "ADMIN";
    public static final String TEACHER = "TEACHER";

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
                        .requestMatchers(HttpMethod.POST, "/api/documents/orders/render").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/documents/orders/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/notify/telegram/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/notify/bindings/codes").hasAnyRole(ADMIN, TEACHER)
                        .requestMatchers("/api/notify/**").hasRole(ADMIN)
                        .requestMatchers("/actuator/**").hasRole(ADMIN)
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
