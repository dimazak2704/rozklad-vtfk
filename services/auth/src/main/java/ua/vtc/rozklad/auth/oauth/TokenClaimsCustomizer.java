package ua.vtc.rozklad.auth.oauth;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.auth.account.Account;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Claims of our tokens (DESIGN §3.3). Access tokens: {@code aud=[client, rozklad-api]}, a flat {@code roles}
 * claim and {@code preferred_username}; user tokens read the account from the database at issuance, so a
 * disabled account gets {@code invalid_grant}. Service tokens keep {@code service-account-<client_id>} as
 * actor name. ID tokens: {@code sub}, {@code preferred_username}, {@code name}, {@code email}.
 */
@Component
public class TokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    public static final String API_AUDIENCE = "rozklad-api";

    private final AccountRepository accounts;
    private final AuthServerProperties properties;

    public TokenClaimsCustomizer(AccountRepository accounts, AuthServerProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        String clientId = context.getRegisteredClient().getClientId();
        boolean service = AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType());
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            context.getClaims().audience(mutableList(List.of(clientId, API_AUDIENCE)));
            if (service) {
                context.getClaims()
                        .claim("preferred_username", "service-account-" + clientId)
                        .claim("roles", mutableList(serviceRoles(clientId).stream().sorted().toList()));
            } else {
                Account account = requireEnabled(context.getPrincipal().getName());
                context.getClaims()
                        .subject(account.id().toString())
                        .claim("preferred_username", account.loginName())
                        .claim("roles", mutableList(account.roles().stream().sorted().toList()));
            }
        } else if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
            Account account = requireEnabled(context.getPrincipal().getName());
            context.getClaims()
                    .subject(account.id().toString())
                    .claim("preferred_username", account.loginName())
                    .claim("name", account.displayName());
            if (account.email() != null) {
                context.getClaims().claim("email", account.email()).claim("email_verified", true);
            }
        }
    }

    /**
     * Claims are stored with the authorization and read back by Jackson with an allowlist of types;
     * JDK immutable lists ({@code List.of}, {@code toList()}) are not on it, {@link ArrayList} is.
     */
    private static List<String> mutableList(List<String> values) {
        return new ArrayList<>(values);
    }

    private Set<String> serviceRoles(String clientId) {
        AuthServerProperties.ServiceClient client = properties.serviceClients().get(clientId);
        return client == null ? Set.of() : client.roles();
    }

    /** The account behind a user token; a missing or disabled one ends the grant with {@code invalid_grant}. */
    private Account requireEnabled(String principalName) {
        UUID id;
        try {
            id = UUID.fromString(principalName);
        } catch (IllegalArgumentException ex) {
            throw invalidGrant();
        }
        return accounts.findById(id).filter(Account::enabled).orElseThrow(TokenClaimsCustomizer::invalidGrant);
    }

    private static OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                "The account is not active", null));
    }
}
