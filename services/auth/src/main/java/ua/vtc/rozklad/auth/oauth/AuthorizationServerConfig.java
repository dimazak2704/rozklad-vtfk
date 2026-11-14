package ua.vtc.rozklad.auth.oauth;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * The OAuth 2.1 / OIDC protocol endpoints (filter chain 1 of DESIGN §4.1): authorize, token, JWKS,
 * discovery, revoke, introspect, userinfo and RP-initiated logout under the fixed issuer. Authorizations,
 * consents and clients are stored in PostgreSQL. A browser without a session is sent to {@code /login}
 * (context-relative: the browser gets {@code /auth/login}).
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http, RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations, OAuth2AuthorizationConsentService consents,
            AuthServerProperties properties) throws Exception {
        http
                .oauth2AuthorizationServer(as -> {
                    http.securityMatcher(as.getEndpointsMatcher());
                    as.registeredClientRepository(clients)
                            // секрети сервісів — дешевий хеш (ClientSecrets), а не BCrypt 12 паролів людей
                            .clientAuthentication(client -> client.authenticationProviders(ClientSecrets::useIn))
                            .authorizationService(authorizations)
                            .authorizationConsentService(consents)
                            .oidc(oidc -> oidc.logoutEndpoint(logout ->
                                    logout.logoutResponseHandler(new LogoutAuthorizationCleanup(authorizations))));
                })
                // prompt=none без сесії має отримати login_required, а не сторінку входу: у Spring Security 7.1
                // фільтр authorize стоїть після AuthorizationFilter, тож тихий запит пропускаємо до нього
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(silentAuthorizeRequest()).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"), browserNavigation()))
                // userinfo приймає наш access token
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .cors(cors -> cors.configurationSource(corsConfiguration(properties)));
        return http.build();
    }

    /** A browser page load (explicit {@code text/html}); API calls without Accept get 401, not the login page. */
    static RequestMatcher browserNavigation() {
        var matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
    }

    /**
     * {@code GET /oauth2/authorize?…prompt=none…}: the silent renew of the SPA. Spring AS itself answers it
     * ({@code login_required} without a session, a code with one); it never shows the sign-in page.
     */
    static RequestMatcher silentAuthorizeRequest() {
        RequestMatcher authorize = PathPatternRequestMatcher.withDefaults().matcher("/oauth2/authorize");
        return request -> authorize.matches(request) && hasPromptNone(request.getParameter("prompt"));
    }

    static boolean hasPromptNone(String prompt) {
        return prompt != null && Arrays.asList(prompt.trim().split("\\s+")).contains("none");
    }

    /** The one issuer of this environment; never derived from the request (a forged Host changes nothing). */
    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthServerProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuerValue()).build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcOperations jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcOperations jdbc,
            RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    /**
     * Browsers call discovery, JWKS and the token endpoint with {@code fetch} from the Vite origins (5180,
     * 5181) in development; in production the SPA is on the same origin and the list is empty. No cookies.
     */
    static CorsConfigurationSource corsConfiguration(AuthServerProperties properties) {
        var source = new UrlBasedCorsConfigurationSource();
        if (!properties.corsOrigins().isEmpty()) {
            var config = new CorsConfiguration();
            config.setAllowedOrigins(List.copyOf(properties.corsOrigins()));
            config.setAllowedMethods(List.of("GET", "POST"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
            config.setAllowCredentials(false);
            config.setMaxAge(Duration.ofHours(1));
            source.registerCorsConfiguration("/**", config);
        }
        return source;
    }
}
