package ua.vtc.rozklad.auth.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Development values from {@code .env.example} and the dev compose file. */
    static final Set<String> DEV_SECRETS = Set.of(
            "dev-notification-secret", "dev-importer-secret", "dev-core-auth-secret", "dev-core-admin-secret",
            "change-me", "changeme");

    private final AuthServerProperties properties;
    private final Environment environment;

    public ProdGuard(AuthServerProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = violations(properties,
                environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
        if (!violations.isEmpty()) {
            throw new IllegalStateException("auth refuses to start with an https issuer:\n - "
                    + String.join("\n - ", violations));
        }
    }

    /** The violated rules; empty for an http issuer (development) or a correct production configuration. */
    static List<String> violations(AuthServerProperties properties, boolean secureCookie) {
        List<String> violations = new ArrayList<>();
        if (!properties.secure()) {
            return violations;
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
        for (Map.Entry<String, AuthServerProperties.ServiceClient> client : properties.serviceClients().entrySet()) {
            String secret = client.getValue().secret();
            if (secret != null && DEV_SECRETS.contains(secret.strip())) {
                violations.add("the secret of " + client.getKey() + " is a known development value");
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
