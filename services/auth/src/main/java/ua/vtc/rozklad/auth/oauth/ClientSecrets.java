package ua.vtc.rozklad.auth.oauth;

import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;

/**
 * How the secrets of the service clients are hashed. The token, introspection and revocation endpoints are
 * public, and Spring AS checks a presented secret before anything else; with the account passwords' BCrypt
 * cost 12 every request with a wrong secret for a known client id would cost about a quarter of a second of
 * CPU. The secrets are random values of at least 32 characters (gen-secrets.sh; {@code ProdGuard} refuses
 * shorter ones in production), so the hash cost adds nothing against guessing: cost 4 (about a millisecond).
 * The matcher accepts any {@code {bcrypt}} hash and never re-encodes one: Spring AS re-encodes a stored secret
 * after a successful check when the encoder asks for it, which with the shared encoder would bring cost 12 back.
 */
public final class ClientSecrets {

    static final int BCRYPT_STRENGTH = 4;

    private static final PasswordEncoder ENCODER = new DelegatingPasswordEncoder("bcrypt",
            Map.of("bcrypt", new BCryptPasswordEncoder(BCRYPT_STRENGTH)));

    private ClientSecrets() {
    }

    /** Encodes a secret for {@code oauth2_registered_client} and checks presented ones. */
    public static PasswordEncoder encoder() {
        return ENCODER;
    }

    /** Makes the client-secret provider of the authorization server use {@link #encoder()}. */
    static void useIn(List<AuthenticationProvider> providers) {
        providers.stream().filter(ClientSecretAuthenticationProvider.class::isInstance)
                .map(ClientSecretAuthenticationProvider.class::cast)
                .forEach(provider -> provider.setPasswordEncoder(ENCODER));
    }
}
