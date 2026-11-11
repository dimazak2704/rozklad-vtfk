package ua.vtc.rozklad.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;

/** Discovery under the fixed issuer with a path ({@code /auth}), and the public JWKS. */
@AuthIntegrationTest
class DiscoveryIT {

    private static final String ISSUER = "http://localhost:8088/auth";

    @Autowired
    private Browsers browsers;

    @Test
    void everyEndpointStartsWithTheIssuer() {
        HttpResponse<String> response = browsers.open().get("/auth/.well-known/openid-configuration");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode discovery = OidcClient.json(response.body());
        assertThat(discovery.path("issuer").asString()).isEqualTo(ISSUER);
        for (String endpoint : List.of("authorization_endpoint", "token_endpoint", "jwks_uri", "userinfo_endpoint",
                "end_session_endpoint", "revocation_endpoint", "introspection_endpoint")) {
            assertThat(discovery.path(endpoint).asString()).as(endpoint).startsWith(ISSUER + "/");
        }
        assertThat(discovery.path("authorization_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/authorize");
        assertThat(discovery.path("end_session_endpoint").asString()).isEqualTo(ISSUER + "/connect/logout");
        assertThat(discovery.path("code_challenge_methods_supported").toString()).contains("S256");
    }

    @Test
    void aForgedHostDoesNotChangeTheIssuer() {
        HttpResponse<String> response = browsers.open().get("/auth/.well-known/openid-configuration",
                "X-Forwarded-Host", "evil.example");

        assertThat(OidcClient.json(response.body()).path("issuer").asString()).isEqualTo(ISSUER);
    }

    @Test
    void jwksPublishesOnlyThePublicKeyWithKid() {
        HttpResponse<String> response = browsers.open().get("/auth/oauth2/jwks");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode key = OidcClient.json(response.body()).path("keys").get(0);
        assertThat(key.path("kty").asString()).isEqualTo("RSA");
        assertThat(key.path("kid").asString()).isNotBlank();
        assertThat(key.path("use").asString()).isEqualTo("sig");
        assertThat(key.has("d")).as("private exponent").isFalse();
        assertThat(key.has("p")).isFalse();
    }

    @Test
    void protocolEndpointsAllowTheViteOriginsOnly() {
        Browser browser = browsers.open();
        HttpResponse<String> allowed = browser.get("/auth/oauth2/jwks", "Origin", "http://localhost:5180");
        HttpResponse<String> other = browser.get("/auth/oauth2/jwks", "Origin", "https://evil.example");

        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5180");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        assertThat(other.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
}
