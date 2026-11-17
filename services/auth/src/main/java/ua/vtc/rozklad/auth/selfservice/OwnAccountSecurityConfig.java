package ua.vtc.rozklad.auth.selfservice;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ua.vtc.rozklad.auth.account.Roles;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * The person's own account API under {@code /api/**} (filter chain 2 of DESIGN §4.1, beside the internal API;
 * their paths do not overlap): stateless, bearer JWT of this service, only people (ADMIN or TEACHER) with an
 * account id as {@code sub}. Service tokens get 403. No CSRF: the request is authorised by the
 * {@code Authorization} header, which a cross-site form cannot send; the sign-in cookie that may come along only
 * says which session to keep. CORS is for the Vite origins of development ({@code AUTH_CORS_ORIGINS}); with
 * credentials, so that cookie comes along there too. On the server the SPA is on the same origin.
 */
@Configuration(proxyBeanMethods = false)
public class OwnAccountSecurityConfig {

    // S4502 (CSRF) і S5876 (фіксація сесії): ланцюжок лише для bearer-JWT, причини — у Javadoc і коментарі нижче
    @SuppressWarnings({"java:S4502", "java:S5876"})
    @Bean
    @Order(2)
    SecurityFilterChain ownAccountChain(HttpSecurity http, AuthServerProperties properties) throws Exception {
        http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().access(staffOnly()))
                // без захисту від фіксації: інакше SessionManagementFilter після bearer-автентифікації міняє id сесії
                // входу, чий cookie прийшов разом із запитом (Set-Cookie у відповіді API), а сервіс бачить новий id,
                // якого ще немає в БД, і видаляє цю сесію разом з іншими. Сесію тут ніхто не автентифікує
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                        .sessionFixation(fixation -> fixation.none()))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfiguration(properties)))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    static AuthorizationManager<RequestAuthorizationContext> staffOnly() {
        return (authentication, context) -> {
            var auth = authentication.get();
            boolean allowed = auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt jwt
                    && isAccountId(jwt.getSubject()) && hasStaffRole(jwt.getClaim("roles"));
            return new AuthorizationDecision(allowed);
        };
    }

    static boolean isAccountId(String subject) {
        if (subject == null) {
            return false;
        }
        try {
            UUID.fromString(subject);
            return true;
        } catch (IllegalArgumentException notAnAccount) {
            return false;
        }
    }

    static boolean hasStaffRole(Object roles) {
        return roles instanceof Collection<?> list && list.stream().anyMatch(Roles.HUMAN::contains);
    }

    static CorsConfigurationSource corsConfiguration(AuthServerProperties properties) {
        var source = new UrlBasedCorsConfigurationSource();
        if (!properties.corsOrigins().isEmpty()) {
            var config = new CorsConfiguration();
            config.setAllowedOrigins(List.copyOf(properties.corsOrigins()));
            config.setAllowedMethods(List.of("GET", "POST"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
            config.setAllowCredentials(true);
            config.setMaxAge(Duration.ofHours(1));
            source.registerCorsConfiguration("/**", config);
        }
        return source;
    }
}
