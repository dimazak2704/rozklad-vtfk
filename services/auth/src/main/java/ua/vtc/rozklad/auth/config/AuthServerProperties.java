package ua.vtc.rozklad.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the sign-in service ({@code app.auth.*}). Every value comes from the environment;
 * {@code application.yml} holds the development defaults.
 *
 * @param issuer the one fixed issuer of this environment, e.g. {@code http://localhost:8088/auth}
 * @param publicWebUrl the site, for «До розкладу» and the default target after a direct sign-in
 * @param webOrigins origins of the SPA; each gets its callback, silent-renew and post-logout URIs
 * @param corsOrigins origins allowed to call the protocol endpoints from a browser
 * @param cliEnabled whether the public client {@code rozklad-cli} (scripts, e2e) exists
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthServerProperties(
        @NotNull URI issuer,
        @NotNull URI publicWebUrl,
        @DefaultValue List<URI> webOrigins,
        @DefaultValue List<String> corsOrigins,
        boolean cliEnabled,
        @Valid @NotNull Tokens tokens,
        @DefaultValue Map<String, ServiceClient> serviceClients,
        @Valid @NotNull SigningKey signingKey,
        @Valid @NotNull Session session,
        @Valid @NotNull Lockout lockout,
        @Valid @NotNull Password password,
        @NotNull Bootstrap bootstrap,
        @NotNull DemoSeed demoSeed) {

    /** True for an {@code https} issuer: production rules apply (startup guards, no dev shortcuts). */
    public boolean secure() {
        return "https".equalsIgnoreCase(issuer.getScheme());
    }

    /** The issuer without a trailing slash, as it appears in {@code iss}. */
    public String issuerValue() {
        String value = issuer.toString();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record Tokens(
            @NotNull Duration webAccess,
            @NotNull Duration cliAccess,
            @NotNull Duration serviceAccess,
            @NotNull Duration authorizationCode) {
    }

    /** A confidential {@code client_credentials} client; an empty secret means "not registered". */
    public record ServiceClient(String secret, @DefaultValue Set<String> roles, @DefaultValue Set<String> scopes) {

        public boolean configured() {
            return StringUtils.hasText(secret);
        }
    }

    public record SigningKey(String file, String keyId, String previousPublicKeyFile, String previousKeyId) {
    }

    /** Absolute session lifetime; the idle timeout is {@code spring.session.timeout}. */
    public record Session(@NotNull Duration maxLifetime) {
    }

    /** Thresholds of {@code LoginThrottleFilter} (DESIGN §4.3). */
    public record Lockout(
            @Min(1) int maxFailures,
            @NotNull Duration window,
            @NotNull Duration baseDelay,
            @NotNull Duration maxDelay,
            @Min(1) int slowdownFailures,
            @NotNull Duration slowdownWindow,
            @NotNull Duration slowdownDelay,
            @Min(1) int networkDistinctIdentifiers,
            @NotNull Duration networkWindow) {
    }

    public record Password(@Min(8) int minLength) {
    }

    /** First admin and break-glass repair (DESIGN §4.10). */
    public record Bootstrap(String username, String email, String password, boolean force) {

        public boolean configured() {
            return StringUtils.hasText(password) && (StringUtils.hasText(username) || StringUtils.hasText(email));
        }
    }

    /** Demo accounts of the local stand (http issuer only). */
    public record DemoSeed(boolean enabled, @DefaultValue List<DemoAccount> accounts) {
    }

    public record DemoAccount(UUID id, String username, String email, String displayName, String role,
            String password) {
    }
}
