package ua.vtc.rozklad.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ua.vtc.rozklad.auth.config.AuthServerProperties.ServiceClient;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

class ProdGuardTest {

    @Test
    void developmentIsNotGuarded() {
        assertThat(ProdGuard.violations(PropertiesFixture.builder().demoSeed(true).build(), false)).isEmpty();
    }

    @Test
    void aCorrectProductionConfigurationPasses() {
        assertThat(ProdGuard.violations(PropertiesFixture.prod().build(), true)).isEmpty();
    }

    @Test
    void theSigningKeyFileIsRequired() {
        var properties = PropertiesFixture.prod()
                .signingKey(new AuthServerProperties.SigningKey("", "k", null, null)).build();

        assertThat(ProdGuard.violations(properties, true)).singleElement().asString().contains("AUTH_SIGNING_KEY_FILE");
    }

    @Test
    void theCookieMustBeSecure() {
        assertThat(ProdGuard.violations(PropertiesFixture.prod().build(), false)).singleElement().asString()
                .contains("Secure");
    }

    @Test
    void developmentOriginsAreRefused() {
        var properties = PropertiesFixture.prod()
                .webOrigins(List.of(URI.create("https://rozklad.example.ua"), URI.create("http://localhost:5180")))
                .corsOrigins(List.of("https://127.0.0.1:8443"))
                .publicWebUrl("http://rozklad.example.ua")
                .build();

        assertThat(ProdGuard.violations(properties, true)).hasSize(3)
                .anySatisfy(v -> assertThat(v).contains("AUTH_WEB_ORIGINS").contains("localhost:5180"))
                .anySatisfy(v -> assertThat(v).contains("AUTH_CORS_ORIGINS"))
                .anySatisfy(v -> assertThat(v).contains("PUBLIC_WEB_URL"));
    }

    @Test
    void theDemoSeedIsRefused() {
        assertThat(ProdGuard.violations(PropertiesFixture.prod().demoSeed(true).build(), true)).singleElement()
                .asString().contains("AUTH_DEMO_SEED");
    }

    @Test
    void knownDevelopmentSecretsAreRefused() {
        var properties = PropertiesFixture.prod().serviceClients(Map.of(
                "rozklad-importer", new ServiceClient("dev-importer-secret", Set.of("SERVICE"), Set.of()),
                "rozklad-core", new ServiceClient("dev-core-auth-secret", Set.of(), Set.of("accounts.manage")),
                "rozklad-notification", new ServiceClient("", Set.of("SERVICE"), Set.of()))).build();

        assertThat(ProdGuard.violations(properties, true)).hasSize(2)
                .allSatisfy(v -> assertThat(v).contains("known development value"));
    }

    /** Service secrets are hashed cheaply (the public token endpoint checks them first), so they must be long. */
    @Test
    void shortServiceSecretsAreRefused() {
        var properties = PropertiesFixture.prod().serviceClients(Map.of(
                "rozklad-core", new ServiceClient("only-twenty-one-chars", Set.of(), Set.of("accounts.manage")),
                "rozklad-importer", new ServiceClient("", Set.of("SERVICE"), Set.of()))).build();

        assertThat(ProdGuard.violations(properties, true)).singleElement().asString()
                .contains("rozklad-core").contains("32");
    }

    /** {@code rozklad-cli} (loopback redirect, any port) only with an explicit override, and then with a warning. */
    @Test
    void theDevelopmentClientIsRefusedUnlessExplicitlyAllowed() {
        var properties = PropertiesFixture.prod().cliEnabled(true).build();
        var secure = new MockEnvironment().withProperty("server.servlet.session.cookie.secure", "true");

        assertThat(ProdGuard.violations(properties, true)).singleElement().asString().contains("AUTH_CLI_ENABLED");
        assertThatThrownBy(new ProdGuard(properties, GoogleProperties.disabled(), secure)::afterPropertiesSet)
                .hasMessageContaining("AUTH_CLI_ALLOW_PROD");
        assertThatCode(new ProdGuard(properties, GoogleProperties.disabled(),
                secure.withProperty("app.auth.cli-allow-prod", "true"))::afterPropertiesSet).doesNotThrowAnyException();
        // розробка (http-видавець) — як і раніше
        assertThat(ProdGuard.violations(PropertiesFixture.builder().cliEnabled(true).build(), false)).isEmpty();
    }

    @Test
    void startupFailsWithEveryViolationListed() {
        var guard = new ProdGuard(PropertiesFixture.prod().demoSeed(true)
                .signingKey(new AuthServerProperties.SigningKey(null, "k", null, null)).build(),
                GoogleProperties.disabled(), new MockEnvironment().withProperty("server.servlet.session.cookie.secure", "true"));

        assertThatThrownBy(guard::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_SIGNING_KEY_FILE").hasMessageContaining("AUTH_DEMO_SEED");
    }

    @Test
    void realGoogleWithTheCollegeDomainPasses() {
        var google = google("real-client-id", "real-google-secret", "vtc.vn.ua", false, false, null);

        assertThat(ProdGuard.violations(PropertiesFixture.prod().build(), true, google)).isEmpty();
    }

    @Test
    void theMockAndEndpointOverridesAreRefused() {
        var google = google("rozklad-dev", "real-google-secret", "vtc.vn.ua", false, true,
                "http://mock-oidc:8090/google");

        assertThat(ProdGuard.violations(PropertiesFixture.prod().build(), true, google)).hasSize(2)
                .anySatisfy(v -> assertThat(v).contains("AUTH_GOOGLE_MOCK"))
                .anySatisfy(v -> assertThat(v).contains("overridden"));
    }

    @Test
    void googleWithoutAHostedDomainNeedsConsumerAccountsExplicitly() {
        var anyDomain = google("real-client-id", "real-google-secret", "", false, false, null);
        var consumers = google("real-client-id", "real-google-secret", "", true, false, null);

        assertThat(ProdGuard.violations(PropertiesFixture.prod().build(), true, anyDomain)).singleElement().asString()
                .contains("AUTH_GOOGLE_HOSTED_DOMAIN");
        assertThat(ProdGuard.violations(PropertiesFixture.prod().build(), true, consumers)).isEmpty();
    }

    @Test
    void theDevelopmentGoogleSecretIsRefused() {
        var google = google("real-client-id", "dev-google-secret", "vtc.vn.ua", false, false, null);

        assertThat(ProdGuard.violations(PropertiesFixture.prod().build(), true, google)).singleElement().asString()
                .contains("Google client secret");
    }

    @Test
    void theLanModeOnAPrivateAddressIsDevelopment() {
        for (String issuer : List.of("https://192.168.31.112/auth", "https://10.0.0.5/auth", "https://172.16.0.1/auth",
                "https://172.31.255.254:8443/auth")) {
            var properties = PropertiesFixture.builder().issuer(issuer).lanDev(true).demoSeed(true).build();

            assertThat(properties.lanDevelopment()).as(issuer).isTrue();
            assertThat(properties.secure()).as(issuer).isFalse();
            assertThat(ProdGuard.violations(properties, false)).as(issuer).isEmpty();
        }
    }

    @Test
    void aPrivateAddressWithoutTheSwitchIsProduction() {
        var properties = PropertiesFixture.builder().issuer("https://192.168.31.112/auth").build();

        assertThat(properties.lanDevelopment()).isFalse();
        assertThat(properties.secure()).isTrue();
        assertThat(ProdGuard.violations(properties, false)).isNotEmpty();
    }

    @Test
    void theLanSwitchNeverRelaxesAPublicIssuer() {
        for (String issuer : List.of("https://rozklad.example.ua/auth", "https://8.8.8.8/auth",
                "https://172.32.0.1/auth", "https://192.169.0.1/auth", "https://192.168.1.1.example.com/auth",
                "https://192.168.1.300/auth")) {
            var properties = PropertiesFixture.prod().issuer(issuer).lanDev(true).build();

            assertThat(properties.secure()).as(issuer).isTrue();
            assertThat(ProdGuard.violations(properties, true)).as(issuer)
                    .anySatisfy(v -> assertThat(v).contains("ROZKLAD_LAN_DEV"));
        }
    }

    @Test
    void theLanSwitchIsIgnoredOnAnHttpIssuer() {
        var properties = PropertiesFixture.builder().lanDev(true).build();

        assertThat(properties.lanDevelopment()).isFalse();
        assertThat(ProdGuard.violations(properties, false)).isEmpty();
    }

    @Test
    void developmentMayUseTheMockWithoutADomain() {
        var google = google("rozklad-dev", "dev-google-secret", "", false, true, "http://mock-oidc:8090/google");

        assertThat(ProdGuard.violations(PropertiesFixture.dev(), false, google)).isEmpty();
    }

    private static GoogleProperties google(String clientId, String secret, String domain, boolean consumers,
            boolean mock, String issuer) {
        return new GoogleProperties(clientId, secret, domain, consumers, mock, issuer, null, null, null, null);
    }
}
