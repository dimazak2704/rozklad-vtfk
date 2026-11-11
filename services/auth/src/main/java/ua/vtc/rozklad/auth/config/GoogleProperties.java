package ua.vtc.rozklad.auth.config;

import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Sign-in with Google ({@code app.auth.google.*}, DESIGN §4.4). Enabled only when {@code clientId} is set;
 * otherwise the sign-in page shows the password form alone.
 *
 * @param hostedDomain the college's Workspace domain ({@code vtc.vn.ua}); an address on it must come with the
 *                     same {@code hd} claim. Empty = no domain rule (local testing only, see {@link ProdGuard})
 * @param allowConsumerAccounts whether addresses outside {@code hostedDomain} may sign in (still only if they
 *                     are in the access list)
 * @param mock         development stand-in for Google ({@code mock-oauth2-server}): only then the endpoint
 *                     overrides below are used; refused with an https issuer
 */
@ConfigurationProperties("app.auth.google")
public record GoogleProperties(
        String clientId,
        String clientSecret,
        String hostedDomain,
        boolean allowConsumerAccounts,
        boolean mock,
        String issuerUri,
        String authorizationUri,
        String tokenUri,
        String jwkSetUri,
        String userInfoUri) {

    /** Google sign-in switched off (no client id). */
    public static GoogleProperties disabled() {
        return new GoogleProperties(null, null, null, false, false, null, null, null, null, null);
    }

    public boolean enabled() {
        return StringUtils.hasText(clientId);
    }

    /** The configured domain in lower case, or {@code null} when there is none. */
    public String domain() {
        return StringUtils.hasText(hostedDomain) ? hostedDomain.strip().toLowerCase(Locale.ROOT) : null;
    }

    /** Any endpoint of the provider is set by hand (only the mock may do that). */
    public boolean hasEndpointOverrides() {
        return Stream.of(issuerUri, authorizationUri, tokenUri, jwkSetUri, userInfoUri).anyMatch(StringUtils::hasText);
    }
}
