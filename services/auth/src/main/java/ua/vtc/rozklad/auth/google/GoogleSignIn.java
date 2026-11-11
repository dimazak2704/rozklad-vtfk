package ua.vtc.rozklad.auth.google;

import java.util.Optional;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ua.vtc.rozklad.auth.config.AuthServerProperties;
import ua.vtc.rozklad.auth.config.GoogleProperties;

/**
 * The Google client registration, built in code so that an empty client id switches Google off instead of
 * failing startup. Real Google comes only from {@link CommonOAuth2Provider#GOOGLE} (endpoint overrides are
 * ignored); explicit endpoints are for the development mock. The redirect URI is fixed from the issuer, never
 * derived from the request.
 */
@Component
public class GoogleSignIn {

    public static final String REGISTRATION_ID = "google";

    private final GoogleProperties properties;
    private final Optional<ClientRegistration> registration;

    public GoogleSignIn(GoogleProperties properties, AuthServerProperties auth) {
        this.properties = properties;
        this.registration = registration(properties, auth);
    }

    public boolean enabled() {
        return registration.isPresent();
    }

    public Optional<ClientRegistration> registration() {
        return registration;
    }

    public GoogleProperties properties() {
        return properties;
    }

    static Optional<ClientRegistration> registration(GoogleProperties google, AuthServerProperties auth) {
        if (!google.enabled()) {
            return Optional.empty();
        }
        ClientRegistration.Builder builder;
        if (google.mock()) {
            require(google.issuerUri(), "AUTH_GOOGLE_ISSUER_URI");
            require(google.authorizationUri(), "AUTH_GOOGLE_AUTHORIZATION_URI");
            require(google.tokenUri(), "AUTH_GOOGLE_TOKEN_URI");
            require(google.jwkSetUri(), "AUTH_GOOGLE_JWK_SET_URI");
            builder = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .scope("openid", "email", "profile")
                    .issuerUri(google.issuerUri())
                    .authorizationUri(google.authorizationUri())
                    .tokenUri(google.tokenUri())
                    .jwkSetUri(google.jwkSetUri())
                    .userInfoUri(StringUtils.hasText(google.userInfoUri()) ? google.userInfoUri() : null)
                    .userNameAttributeName(IdTokenClaimNames.SUB)
                    .clientName("Google (mock)");
        } else {
            builder = CommonOAuth2Provider.GOOGLE.getBuilder(REGISTRATION_ID);
        }
        return Optional.of(builder
                .clientId(google.clientId().strip())
                .clientSecret(google.clientSecret() == null ? "" : google.clientSecret().strip())
                .redirectUri(redirectUri(auth))
                .build());
    }

    /** {@code <issuer>/login/oauth2/code/google}: what the owner registers in Google Cloud Console. */
    public static String redirectUri(AuthServerProperties auth) {
        return auth.issuerValue() + "/login/oauth2/code/" + REGISTRATION_ID;
    }

    private static void require(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("AUTH_GOOGLE_MOCK=true needs " + name);
        }
    }
}
