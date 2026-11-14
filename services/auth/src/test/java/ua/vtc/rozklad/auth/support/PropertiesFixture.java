package ua.vtc.rozklad.auth.support;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ua.vtc.rozklad.auth.config.AuthServerProperties;
import ua.vtc.rozklad.auth.config.AuthServerProperties.ServiceClient;

/** {@link AuthServerProperties} for unit tests: the development defaults and a valid production set. */
public final class PropertiesFixture {

    private PropertiesFixture() {
    }

    public static AuthServerProperties dev() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A production configuration that passes every startup guard. */
    public static Builder prod() {
        return builder()
                .issuer("https://rozklad.example.ua/auth")
                .publicWebUrl("https://rozklad.example.ua")
                .webOrigins(List.of(URI.create("https://rozklad.example.ua")))
                .corsOrigins(List.of())
                .signingKey(new AuthServerProperties.SigningKey("/run/secrets/auth-signing-key.pem", "2026-11-a", null,
                        null))
                .serviceClients(Map.of("rozklad-notification",
                        new ServiceClient("test-only-notification-secret-32", Set.of("SERVICE"), Set.of())));
    }

    public static final class Builder {

        private URI issuer = URI.create("http://localhost:8088/auth");
        private URI publicWebUrl = URI.create("http://localhost:8088");
        private List<URI> webOrigins = List.of(URI.create("http://localhost:5180"), URI.create("http://localhost:5181"),
                URI.create("http://localhost:8088"));
        private List<String> corsOrigins = List.of("http://localhost:5180", "http://localhost:5181");
        private boolean cliEnabled;
        private Map<String, ServiceClient> serviceClients = new LinkedHashMap<>(Map.of(
                "rozklad-notification", new ServiceClient("dev-notification-secret", Set.of("SERVICE"), Set.of()),
                "rozklad-core", new ServiceClient("dev-core-auth-secret", Set.of(), Set.of("accounts.manage"))));
        private AuthServerProperties.SigningKey signingKey = new AuthServerProperties.SigningKey(null, "dev", null, null);
        private boolean demoSeed;
        private List<AuthServerProperties.DemoAccount> demoAccounts = List.of();
        private AuthServerProperties.Bootstrap bootstrap = new AuthServerProperties.Bootstrap(null, null, null, false);

        public Builder issuer(String value) {
            this.issuer = URI.create(value);
            return this;
        }

        public Builder publicWebUrl(String value) {
            this.publicWebUrl = URI.create(value);
            return this;
        }

        public Builder webOrigins(List<URI> value) {
            this.webOrigins = value;
            return this;
        }

        public Builder corsOrigins(List<String> value) {
            this.corsOrigins = value;
            return this;
        }

        public Builder cliEnabled(boolean value) {
            this.cliEnabled = value;
            return this;
        }

        public Builder serviceClients(Map<String, ServiceClient> value) {
            this.serviceClients = value;
            return this;
        }

        public Builder signingKey(AuthServerProperties.SigningKey value) {
            this.signingKey = value;
            return this;
        }

        public Builder demoSeed(boolean value) {
            this.demoSeed = value;
            return this;
        }

        public Builder demoAccounts(List<AuthServerProperties.DemoAccount> value) {
            this.demoAccounts = value;
            return this;
        }

        public Builder bootstrap(AuthServerProperties.Bootstrap value) {
            this.bootstrap = value;
            return this;
        }

        public AuthServerProperties build() {
            return new AuthServerProperties(issuer, publicWebUrl, webOrigins, corsOrigins, cliEnabled,
                    new AuthServerProperties.Tokens(Duration.ofMinutes(15), Duration.ofMinutes(15),
                            Duration.ofMinutes(5), Duration.ofSeconds(60)),
                    serviceClients, signingKey, new AuthServerProperties.Session(Duration.ofHours(24)),
                    new AuthServerProperties.Lockout(10, Duration.ofHours(12), Duration.ofSeconds(60),
                            Duration.ofMinutes(15), 50, Duration.ofHours(24), Duration.ofSeconds(2), 20,
                            Duration.ofMinutes(15)),
                    new AuthServerProperties.Password(12),
                    bootstrap,
                    new AuthServerProperties.DemoSeed(demoSeed, null, demoAccounts));
        }
    }
}
