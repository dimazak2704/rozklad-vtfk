package ua.vtc.rozklad.auth.google;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Component;

/**
 * Adds Google to the sign-in chain when it is configured: {@code /auth/oauth2/authorization/google} starts it
 * (with {@code hd} as a hint for Google's account chooser), {@code /auth/login/oauth2/code/google} finishes it.
 * Google's tokens are not kept: the allowlist decides once and the session then holds our own principal.
 */
@Component
public class GoogleLogin {

    private final GoogleSignIn google;
    private final AllowlistOidcUserService users;
    private final GoogleLoginHandlers handlers;

    public GoogleLogin(GoogleSignIn google, AllowlistOidcUserService users, GoogleLoginHandlers handlers) {
        this.google = google;
        this.users = users;
        this.handlers = handlers;
    }

    public boolean enabled() {
        return google.enabled();
    }

    /**
     * The provider that exchanges Google's code, validates the ID token and asks the allowlist. The sign-in
     * chain has its own {@code AuthenticationManager} (password provider without a global parent), so the
     * providers {@code oauth2Login()} would register are added here explicitly.
     */
    public List<AuthenticationProvider> authenticationProviders() {
        if (google.registration().isEmpty()) {
            return List.of();
        }
        return List.of(new OidcAuthorizationCodeAuthenticationProvider(
                new RestClientAuthorizationCodeTokenResponseClient(), users));
    }

    public void configure(HttpSecurity http) throws Exception {
        if (google.registration().isEmpty()) {
            return;
        }
        ClientRegistrationRepository clients = new InMemoryClientRegistrationRepository(google.registration().get());
        http.oauth2Login(login -> login
                .loginPage("/login")
                .clientRegistrationRepository(clients)
                .authorizedClientRepository(new DiscardingAuthorizedClients())
                .authorizationEndpoint(authorization -> authorization
                        .authorizationRequestResolver(resolver(clients, google.properties().domain())))
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(users))
                .successHandler(handlers.successHandler())
                .failureHandler(handlers.failureHandler()));
    }

    /** {@code hd} narrows Google's account chooser to the college domain; it is a hint, the allowlist decides. */
    static DefaultOAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository clients, String domain) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        if (domain != null) {
            resolver.setAuthorizationRequestCustomizer(request -> request
                    .additionalParameters(parameters -> parameters.put("hd", domain)));
        }
        return resolver;
    }

    /** Google's access token is never needed after the sign-in, so it is not stored anywhere. */
    static final class DiscardingAuthorizedClients implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId,
                Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            // навмисно нічого: токени Google не зберігаємо
        }

        @Override
        public void removeAuthorizedClient(String registrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            // нічого не зберігали
        }
    }
}
