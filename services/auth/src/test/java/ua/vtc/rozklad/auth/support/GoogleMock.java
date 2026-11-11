package ua.vtc.rozklad.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.nav.security.mock.oauth2.MockOAuth2Server;
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Google stand-in for {@code GoogleLoginIT}: {@code mock-oauth2-server} in the test JVM (no Docker), issuer id
 * {@code google}, non-interactive (the authorize endpoint redirects back with a code at once). The claims of
 * the next ID token are set with {@link #nextUser}. The registration of {@code auth} points at it through
 * {@code app.auth.google.*} (mock mode).
 */
@TestConfiguration(proxyBeanMethods = false)
public class GoogleMock {

    public static final String ISSUER_ID = "google";
    public static final String CLIENT_ID = "it-google-client";
    /** Test-only secret of the mock client (the mock accepts any). */
    public static final String CLIENT_SECRET = "it-google-secret";

    @Bean(destroyMethod = "shutdown")
    MockOAuth2Server googleMockServer() {
        var server = new MockOAuth2Server();
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar googleMockProperties(MockOAuth2Server server) {
        return registry -> {
            registry.add("app.auth.google.issuer-uri", () -> server.issuerUrl(ISSUER_ID).toString());
            registry.add("app.auth.google.authorization-uri",
                    () -> server.authorizationEndpointUrl(ISSUER_ID).toString());
            registry.add("app.auth.google.token-uri", () -> server.tokenEndpointUrl(ISSUER_ID).toString());
            registry.add("app.auth.google.jwk-set-uri", () -> server.jwksUrl(ISSUER_ID).toString());
            registry.add("app.auth.google.user-info-uri", () -> server.userInfoUrl(ISSUER_ID).toString());
        };
    }

    @Bean
    Users googleUsers(MockOAuth2Server server) {
        return new Users(server);
    }

    /** Who "signs in at Google" next, and the browser step at Google. */
    public static final class Users {

        private final MockOAuth2Server server;
        private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();

        Users(MockOAuth2Server server) {
            this.server = server;
        }

        /**
         * The next ID token (and userinfo) of the mock: {@code sub}, {@code email}, {@code email_verified} and,
         * unless {@code null}, {@code hd}.
         */
        public void nextUser(String subject, String email, boolean emailVerified, String hostedDomain) {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("email", email);
            claims.put("email_verified", emailVerified);
            claims.put("name", "Тест Google");
            if (hostedDomain != null) {
                claims.put("hd", hostedDomain);
            }
            server.enqueueCallback(new DefaultOAuth2TokenCallback(ISSUER_ID, subject, "JWT", List.of(CLIENT_ID),
                    claims, 3600));
        }

        /** The browser at Google: opens the authorization URL and returns the redirect back to {@code auth}. */
        public URI authorize(String authorizationUrl) {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(authorizationUrl)).GET()
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as("mock authorize").isEqualTo(302);
                return URI.create(Browser.location(response));
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }
}
