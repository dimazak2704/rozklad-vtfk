package ua.vtc.rozklad.core.identity.internal;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client of {@link AuthServiceIdentityProvider}: base URL of the internal accounts API, timeouts
 * (5 s connect, 15 s read) and a {@code client_credentials} token of the registration
 * {@value #REGISTRATION_ID} (client {@code rozklad-core}, scope {@code accounts.manage}). Every request
 * carries {@code X-Actor}. The authorized-client manager is private to this client, so it never meets the
 * user tokens of the resource server.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountsApiConfig.AccountsApiProperties.class)
class AccountsApiConfig {

    static final String REST_CLIENT = "authAccountsRestClient";
    /** Registration id in {@code spring.security.oauth2.client.registration.*}. */
    static final String REGISTRATION_ID = "rozklad-auth-admin";

    // токен сервісного акаунта не залежить від користувача запиту
    private static final Authentication CORE_PRINCIPAL = new AnonymousAuthenticationToken("rozklad-core",
            "rozklad-core", List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));

    @Bean(REST_CLIENT)
    RestClient authAccountsRestClient(RestClient.Builder builder, AccountsApiProperties properties,
                                      ClientRegistrationRepository registrations,
                                      OAuth2AuthorizedClientService authorizedClients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        var token = new OAuth2ClientHttpRequestInterceptor(manager);
        token.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        token.setPrincipalResolver(request -> CORE_PRINCIPAL);
        // 401/403 від auth: забути токен, наступний виклик отримає новий
        token.setAuthorizationFailureHandler(
                OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(authorizedClients));

        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build());
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(properties.url().toString())
                .requestFactory(requestFactory)
                .requestInterceptor(actorHeader())
                .requestInterceptor(token)
                .build();
    }

    /** {@code X-Actor}: the admin of the current request, else {@code rozklad-core}. */
    static ClientHttpRequestInterceptor actorHeader() {
        return (request, body, execution) -> {
            request.getHeaders().set(AuthServiceIdentityProvider.ACTOR_HEADER,
                    AuthServiceIdentityProvider.currentActor());
            return execution.execute(request, body);
        };
    }

    /**
     * {@code app.identity.accounts-api.*}.
     *
     * @param url base URL of the internal API of {@code auth} ({@code AUTH_INTERNAL_URL})
     */
    @Validated
    @ConfigurationProperties("app.identity.accounts-api")
    record AccountsApiProperties(
            @NotNull @DefaultValue("http://localhost:8085/auth/internal") URI url,
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            @NotNull @DefaultValue("15s") Duration readTimeout) {
    }
}
