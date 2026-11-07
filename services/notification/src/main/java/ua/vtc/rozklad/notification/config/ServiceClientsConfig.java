package ua.vtc.rozklad.notification.config;

import java.util.List;
import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import ua.vtc.rozklad.notification.web.RequestIdFilter;

/**
 * Outgoing calls with a client-credentials token of the service account
 * {@code rozklad-notification} (realm role SERVICE): {@code coreRestClient} (internal and public
 * core API) and {@code importerRestClient} (free-text absence parsing). Every RestClient forwards
 * {@code X-Request-Id}.
 */
@Configuration(proxyBeanMethods = false)
public class ServiceClientsConfig {

    /** Registration id in {@code spring.security.oauth2.client.registration.*}. */
    public static final String REGISTRATION_ID = "rozklad-service";

    // токен сервісного акаунта не залежить від користувача запиту
    private static final Authentication SERVICE_PRINCIPAL = new AnonymousAuthenticationToken("rozklad-notification",
            "rozklad-notification", List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));

    @Bean
    RestClientCustomizer requestIdRestClientCustomizer() {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null && !request.getHeaders().containsHeader(RequestIdFilter.HEADER)) {
                request.getHeaders().set(RequestIdFilter.HEADER, requestId);
            }
            return execution.execute(request, body);
        };
        return builder -> builder.requestInterceptor(interceptor);
    }

    @Bean
    OAuth2AuthorizedClientManager serviceAuthorizedClientManager(ClientRegistrationRepository registrations,
                                                                 OAuth2AuthorizedClientService authorizedClients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    RestClient coreRestClient(RestClient.Builder builder, OAuth2AuthorizedClientManager serviceAuthorizedClientManager,
                              ServiceUrlsProperties urls) {
        return builder.clone()
                .baseUrl(urls.coreUrl().toString())
                .requestInterceptor(serviceToken(serviceAuthorizedClientManager))
                .build();
    }

    @Bean
    RestClient importerRestClient(RestClient.Builder builder,
                                  OAuth2AuthorizedClientManager serviceAuthorizedClientManager,
                                  ServiceUrlsProperties urls) {
        return builder.clone()
                .baseUrl(urls.importerUrl().toString())
                .requestInterceptor(serviceToken(serviceAuthorizedClientManager))
                .build();
    }

    static OAuth2ClientHttpRequestInterceptor serviceToken(OAuth2AuthorizedClientManager manager) {
        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        interceptor.setPrincipalResolver(request -> SERVICE_PRINCIPAL);
        return interceptor;
    }
}
