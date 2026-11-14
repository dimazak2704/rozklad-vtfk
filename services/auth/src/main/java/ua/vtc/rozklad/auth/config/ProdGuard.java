package ua.vtc.rozklad.auth.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Startup guards for production (DESIGN §5.8), active when the issuer is {@code https://…}: the service
 * refuses to start with development shortcuts, so one forgotten env value cannot ship them. Every
 * violation is listed in one message.
 */
@Component
public class ProdGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProdGuard.class);

    /**
     * The shortest service client secret in production: they are hashed with a low BCrypt cost (the public token
     * endpoint checks them first), which is safe only for long random values; gen-secrets.sh makes 32 characters.
     */
    static final int MIN_SERVICE_SECRET_LENGTH = 32;

    /** {@code AUTH_CLI_ALLOW_PROD}: the explicit consent to {@code rozklad-cli} with an https issuer. */
    static final String CLI_ALLOW_PROD = "app.auth.cli-allow-prod";

    /** Development values from {@code .env.example} and the dev compose file. */
    static final Set<String> DEV_SECRETS = Set.of(
            "dev-notification-secret", "dev-importer-secret", "dev-core-auth-secret", "dev-core-admin-secret",
            "dev-google-secret",
            "change-me", "changeme");

    private final AuthServerProperties properties;
    private final GoogleProperties google;
    private final Environment environment;

    public ProdGuard(AuthServerProperties properties, GoogleProperties google, Environment environment) {
        this.properties = properties;
        this.google = google;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean cliAllowed = environment.getProperty(CLI_ALLOW_PROD, Boolean.class, false);
        List<String> violations = violations(properties,
                environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false), google,
                cliAllowed);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("auth refuses to start with an https issuer:\n - "
                    + String.join("\n - ", violations));
        }
        if (properties.lanDevelopment()) {
            log.warn("development LAN mode (ROZKLAD_LAN_DEV): the https issuer {} is on a private address, so the "
                    + "production guards are off and the development accounts stay", properties.issuerValue());
        }
        if (properties.secure() && properties.cliEnabled()) {
            log.warn("the development client rozklad-cli is registered on a production issuer (AUTH_CLI_ENABLED and "
                    + "AUTH_CLI_ALLOW_PROD are on): switch both off after the one-off run");
        }
    }

    /** The violated rules without Google sign-in. */
    static List<String> violations(AuthServerProperties properties, boolean secureCookie) {
        return violations(properties, secureCookie, GoogleProperties.disabled());
    }

    /** The violated rules without the consent to {@code rozklad-cli}. */
    static List<String> violations(AuthServerProperties properties, boolean secureCookie, GoogleProperties google) {
        return violations(properties, secureCookie, google, false);
    }

    /** The violated rules; empty for an http issuer (development) or a correct production configuration. */
    static List<String> violations(AuthServerProperties properties, boolean secureCookie, GoogleProperties google,
            boolean cliAllowed) {
        List<String> violations = new ArrayList<>();
        if (!properties.secure()) {
            return violations;
        }
        if (properties.lanDev()) {
            violations.add("ROZKLAD_LAN_DEV is on, but the issuer is not on a private IPv4 address (the LAN mode is "
                    + "for the development stand only)");
        }
        if (!StringUtils.hasText(properties.signingKey().file())) {
            violations.add("AUTH_SIGNING_KEY_FILE is not set (an in-memory key is for development only)");
        }
        if (!secureCookie) {
            violations.add("the session cookie must be Secure (AUTH_COOKIE_SECURE=true)");
        }
        if (!isPublicHttps(properties.publicWebUrl())) {
            violations.add("PUBLIC_WEB_URL must be an https address that is not localhost");
        }
        for (URI origin : properties.webOrigins()) {
            if (!isPublicHttps(origin)) {
                violations.add("AUTH_WEB_ORIGINS contains a development origin: " + origin);
            }
        }
        for (String origin : properties.corsOrigins()) {
            if (!isPublicHttps(URI.create(origin))) {
                violations.add("AUTH_CORS_ORIGINS contains a development origin: " + origin);
            }
        }
        if (properties.demoSeed().enabled()) {
            violations.add("AUTH_DEMO_SEED is on (development accounts with short passwords)");
        }
        if (properties.cliEnabled() && !cliAllowed) {
            violations.add("AUTH_CLI_ENABLED is on: the development client rozklad-cli (a loopback redirect on any "
                    + "port, no consent) needs AUTH_CLI_ALLOW_PROD=true as well, for a one-off run only");
        }
        if (google.mock()) {
            violations.add("AUTH_GOOGLE_MOCK is on (the development stand-in for Google)");
        }
        if (google.hasEndpointOverrides()) {
            violations.add("Google endpoints are overridden (AUTH_GOOGLE_*_URI); production uses Google's own");
        }
        if (google.enabled() && google.domain() == null && !google.allowConsumerAccounts()) {
            violations.add("Google sign-in needs AUTH_GOOGLE_HOSTED_DOMAIN (or AUTH_GOOGLE_ALLOW_CONSUMER_ACCOUNTS=true)");
        }
        if (google.clientSecret() != null && DEV_SECRETS.contains(google.clientSecret().strip())) {
            violations.add("the Google client secret is a known development value");
        }
        for (Map.Entry<String, AuthServerProperties.ServiceClient> client : properties.serviceClients().entrySet()) {
            if (!client.getValue().configured()) {
                continue;
            }
            String secret = client.getValue().secret().strip();
            if (DEV_SECRETS.contains(secret)) {
                violations.add("the secret of " + client.getKey() + " is a known development value");
            } else if (secret.length() < MIN_SERVICE_SECRET_LENGTH) {
                violations.add("the secret of " + client.getKey() + " is shorter than " + MIN_SERVICE_SECRET_LENGTH
                        + " characters (gen-secrets.sh makes random ones of that length)");
            }
        }
        return violations;
    }

    private static boolean isPublicHttps(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && !host.equalsIgnoreCase("localhost") && !host.endsWith(".localhost")
                && !host.startsWith("127.") && !host.equals("[::1]") && !host.equals("::1");
    }
}
