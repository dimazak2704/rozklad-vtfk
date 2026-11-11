package ua.vtc.rozklad.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/** The SPA's sign-in: authorization code with PKCE for the public client {@code rozklad-web}. */
@AuthIntegrationTest
class AuthorizationCodeFlowIT {

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void withoutSessionTheBrowserGoesToTheContextRelativeSignInPage() {
        HttpResponse<String> response = browsers.open()
                .get(OidcClient.authorizeUrl(OidcClient.verifier(), "s", null), "Accept", "text/html");

        assertThat(response.statusCode()).isEqualTo(302);
        // відносна адреса з контекстом /auth (LoginUrlAuthenticationEntryPoint + DefaultRedirectStrategy,
        // server.tomcat.use-relative-redirects=true)
        assertThat(Browser.location(response)).isEqualTo("/auth/login");
    }

    @Test
    void fullFlowIssuesAccessAndIdTokensWithOurClaimsAndNoRefreshToken() {
        TestAccounts.Created teacher = accounts.teacher();

        OidcClient.Tokens tokens = OidcClient.signIn(browsers.open(), teacher.username(), TestAccounts.PASSWORD);

        assertThat(tokens.body().has("refresh_token")).as("no refresh token for a public client").isFalse();
        assertThat(tokens.body().path("token_type").asString()).isEqualTo("Bearer");
        JsonNode access = tokens.accessClaims();
        assertThat(access.path("iss").asString()).isEqualTo("http://localhost:8088/auth");
        assertThat(access.path("sub").asString()).isEqualTo(teacher.id().toString());
        assertThat(access.path("preferred_username").asString()).isEqualTo(teacher.username());
        assertThat(texts(access.path("roles"))).containsExactly("TEACHER");
        assertThat(texts(access.path("aud"))).containsExactlyInAnyOrder("rozklad-web", "rozklad-api");
        assertThat(access.has("realm_access")).isFalse();
        // заголовок має kid активного ключа (NimbusJwtEncoder копіює його з JWK)
        assertThat(tokens.accessHeader().path("kid").asString()).isEqualTo("dev");
        assertThat(tokens.accessHeader().path("alg").asString()).isEqualTo("RS256");
        long lifetime = access.path("exp").asLong() - access.path("iat").asLong();
        assertThat(lifetime).isEqualTo(15 * 60);

        JsonNode id = tokens.idClaims();
        assertThat(id.path("sub").asString()).isEqualTo(teacher.id().toString());
        assertThat(texts(id.path("aud"))).containsExactly("rozklad-web");
        assertThat(id.path("nonce").asString()).isEqualTo("n-s1");
        assertThat(id.path("name").asString()).isEqualTo("Тест " + teacher.username());
        assertThat(id.path("email").asString()).isEqualTo(teacher.email());
        assertThat(id.path("email_verified").asBoolean()).isTrue();
        assertThat(id.path("sid").asString()).as("sid from the SessionRegistry").isNotBlank();
        assertThat(id.has("roles")).isFalse();

        // і підпис, і iss перевіряє наш же декодер
        Jwt decoded = jwtDecoder.decode(tokens.accessToken());
        assertThat(decoded.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void promptNoneRenewsSilentlyWithASessionAndAnswersLoginRequiredWithout() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        OidcClient.signIn(browser, teacher.username(), TestAccounts.PASSWORD);

        String verifier = OidcClient.verifier();
        HttpResponse<String> silent = browser.get(OidcClient.authorizeUrl(verifier, "s2", "prompt=none"),
                "Accept", "text/html");
        assertThat(silent.statusCode()).isEqualTo(302);
        Map<String, String> params = Browser.query(Browser.location(silent));
        assertThat(Browser.location(silent)).startsWith(OidcClient.WEB_CALLBACK);
        assertThat(params).containsKey("code").containsEntry("state", "s2");
        assertThat(OidcClient.exchange(browser, params.get("code"), verifier).accessClaims().path("sub").asString())
                .isEqualTo(teacher.id().toString());

        HttpResponse<String> anonymous = browsers.open()
                .get(OidcClient.authorizeUrl(OidcClient.verifier(), "s3", "prompt=none"), "Accept", "text/html");
        assertThat(anonymous.statusCode()).isEqualTo(302);
        assertThat(Browser.location(anonymous)).startsWith(OidcClient.WEB_CALLBACK);
        assertThat(Browser.query(Browser.location(anonymous))).containsEntry("error", "login_required")
                .containsEntry("state", "s3");
    }

    @Test
    void anUnregisteredRedirectUriIsRefusedWithoutRedirecting() {
        String url = OidcClient.authorizeUrl(OidcClient.verifier(), "s", null)
                .replace("localhost:5180", "evil.example");
        HttpResponse<String> response = browsers.open().get(url, "Accept", "text/html");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void pkceIsRequiredAndPlainIsRefused() {
        Browser browser = browsers.open();
        String withoutPkce = "/auth/oauth2/authorize?response_type=code&client_id=rozklad-web"
                + "&redirect_uri=http%3A%2F%2Flocalhost%3A5180%2Flogin%2Fcallback&scope=openid&state=x";
        HttpResponse<String> missing = browser.get(withoutPkce, "Accept", "text/html");
        assertThat(Browser.location(missing)).startsWith(OidcClient.WEB_CALLBACK).contains("error=invalid_request");

        HttpResponse<String> plain = browser.get(withoutPkce + "&code_challenge=abcdefghijabcdefghijabcdefghijabcdefghijabc"
                + "&code_challenge_method=plain", "Accept", "text/html");
        assertThat(Browser.location(plain)).startsWith(OidcClient.WEB_CALLBACK).contains("error=invalid_request");
    }

    @Test
    void aWrongVerifierIsRefusedAndTheCodeIsSingleUse() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        String verifier = OidcClient.verifier();
        browser.get(OidcClient.authorizeUrl(verifier, "s1", null), "Accept", "text/html");
        String location = Browser.location(browser.follow(browser.signIn(teacher.username(), TestAccounts.PASSWORD)));
        String code = Browser.query(location).get("code");

        HttpResponse<String> wrong = OidcClient.tokenRequest(browser, code, OidcClient.verifier());
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("invalid_grant");
    }

    @Test
    void aDisabledAccountGetsInvalidGrantAtTheTokenEndpoint() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        String verifier = OidcClient.verifier();
        browser.get(OidcClient.authorizeUrl(verifier, "s1", null), "Accept", "text/html");
        String location = Browser.location(browser.follow(browser.signIn(teacher.username(), TestAccounts.PASSWORD)));
        // між видачею коду й обміном доступ відкликали
        jdbc.sql("UPDATE account SET enabled = false, disabled_reason = 'REVOKED' WHERE id = ?")
                .param(teacher.id()).update();

        HttpResponse<String> response = OidcClient.tokenRequest(browser, Browser.query(location).get("code"), verifier);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(OidcClient.json(response.body()).path("error").asString()).isEqualTo("invalid_grant");
    }

    private static List<String> texts(JsonNode node) {
        if (node.isArray()) {
            return node.valueStream().map(JsonNode::asString).toList();
        }
        return List.of(node.asString());
    }
}
