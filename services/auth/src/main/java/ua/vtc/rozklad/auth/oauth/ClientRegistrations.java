package ua.vtc.rozklad.auth.oauth;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * The registered clients of DESIGN §3.2, built from configuration. Ids equal client ids, so a restart
 * updates rows in place. No client needs consent; public clients require PKCE (S256 only).
 */
public final class ClientRegistrations {

    public static final String WEB = "rozklad-web";
    public static final String CLI = "rozklad-cli";
    public static final String CALLBACK_PATH = "/login/callback";
    public static final String SILENT_PATH = "/login/silent.html";
    /** Loopback redirect of {@code rozklad-cli}: any port is accepted for an IP literal. */
    public static final String CLI_REDIRECT = "http://127.0.0.1/cli-callback";

    private ClientRegistrations() {
    }

    /**
     * @param encodeSecret encodes a client secret for storage ({@code {bcrypt}…})
     */
    public static List<RegisteredClient> build(AuthServerProperties properties, UnaryOperator<String> encodeSecret) {
        List<RegisteredClient> clients = new ArrayList<>();
        clients.add(web(properties));
        if (properties.cliEnabled()) {
            clients.add(cli(properties));
        }
        for (Map.Entry<String, AuthServerProperties.ServiceClient> entry : properties.serviceClients().entrySet()) {
            if (entry.getValue().configured()) {
                clients.add(service(entry.getKey(), entry.getValue(), properties, encodeSecret));
            }
        }
        return clients;
    }

    /** Origins of all registered redirect URIs, for the {@code form-action} of the sign-in page. */
    public static Set<String> redirectOrigins(AuthServerProperties properties) {
        Set<String> origins = new LinkedHashSet<>();
        for (URI origin : properties.webOrigins()) {
            origins.add(origin(origin));
        }
        if (properties.cliEnabled()) {
            // будь-який порт loopback-адреси
            origins.add("http://127.0.0.1:*");
        }
        return origins;
    }

    static String origin(URI uri) {
        String value = uri.getScheme() + "://" + uri.getHost();
        return uri.getPort() > 0 ? value + ":" + uri.getPort() : value;
    }

    private static RegisteredClient web(AuthServerProperties properties) {
        var builder = RegisteredClient.withId(WEB)
                .clientId(WEB)
                .clientName("Розклад ВТФК (сайт)")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(publicClientSettings())
                .tokenSettings(userTokens(properties.tokens().webAccess(), properties));
        for (URI originUri : properties.webOrigins()) {
            String origin = origin(originUri);
            builder.redirectUri(origin + CALLBACK_PATH)
                    .redirectUri(origin + SILENT_PATH)
                    .postLogoutRedirectUri(origin + "/");
        }
        return builder.build();
    }

    private static RegisteredClient cli(AuthServerProperties properties) {
        return RegisteredClient.withId(CLI)
                .clientId(CLI)
                .clientName("Скрипти й e2e (розробка)")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(CLI_REDIRECT)
                .scope(OidcScopes.OPENID)
                .clientSettings(publicClientSettings())
                .tokenSettings(userTokens(properties.tokens().cliAccess(), properties))
                .build();
    }

    private static RegisteredClient service(String clientId, AuthServerProperties.ServiceClient config,
            AuthServerProperties properties, UnaryOperator<String> encodeSecret) {
        var builder = RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientName(clientId)
                .clientSecret(encodeSecret.apply(config.secret()))
                // лише Basic: секрет у тілі (client_secret_post) Spring AS відхиляє ще до перевірки хешу
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.tokens().serviceAccess())
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .build());
        config.scopes().forEach(builder::scope);
        return builder.build();
    }

    private static ClientSettings publicClientSettings() {
        return ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build();
    }

    private static TokenSettings userTokens(java.time.Duration accessTtl, AuthServerProperties properties) {
        return TokenSettings.builder()
                .accessTokenTimeToLive(accessTtl)
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .authorizationCodeTimeToLive(properties.tokens().authorizationCode())
                .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }
}
