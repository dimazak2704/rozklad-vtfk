package ua.vtc.rozklad.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * Service tokens ({@code client_credentials}) and who may call the internal accounts API with them:
 * only {@code rozklad-core} with scope {@code accounts.manage}.
 */
@AuthIntegrationTest
class ClientCredentialsIT {

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Test
    void notificationGetsAServiceTokenWithTheSubjectAndActorItExpects() {
        JsonNode claims = OidcClient.jwtPart(token("rozklad-notification", AuthIntegrationTest.NOTIFICATION_SECRET, null), 1);

        assertThat(claims.path("sub").asString()).isEqualTo("rozklad-notification");
        assertThat(claims.path("preferred_username").asString()).isEqualTo("service-account-rozklad-notification");
        assertThat(claims.path("roles").valueStream().map(JsonNode::asString).toList()).containsExactly("SERVICE");
        assertThat(claims.path("aud").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("rozklad-notification", "rozklad-api");
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(300);
    }

    @Test
    void aWrongSecretIsRefused() {
        HttpResponse<String> response = tokenResponse("rozklad-importer", "wrong-secret", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("invalid_client");
    }

    @Test
    void publicClientsCannotUseClientCredentials() {
        HttpResponse<String> response = browsers.open().postForm("/auth/oauth2/token",
                Map.of("grant_type", "client_credentials", "client_id", "rozklad-web"));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void onlyCoreWithItsScopeMayReadAccounts() {
        TestAccounts.Created teacher = accounts.teacher();
        String path = "/auth/internal/accounts/" + teacher.id();
        Browser browser = browsers.open();

        String core = token("rozklad-core", AuthIntegrationTest.CORE_SECRET, "accounts.manage");
        HttpResponse<String> allowed = browser.get(path, "Authorization", "Bearer " + core);
        assertThat(allowed.statusCode()).isEqualTo(200);
        JsonNode account = OidcClient.json(allowed.body());
        assertThat(account.path("username").asString()).isEqualTo(teacher.username());
        assertThat(account.path("roles").valueStream().map(JsonNode::asString).toList()).containsExactly("TEACHER");
        assertThat(account.path("hasPassword").asBoolean()).isTrue();
        assertThat(account.path("google").path("linked").asBoolean()).isFalse();
        assertThat(allowed.body()).doesNotContain("bcrypt").doesNotContain("passwordHash");

        String notification = token("rozklad-notification", AuthIntegrationTest.NOTIFICATION_SECRET, null);
        assertThat(browser.get(path, "Authorization", "Bearer " + notification).statusCode()).isEqualTo(403);
        assertThat(browser.get(path).statusCode()).isEqualTo(401);

        // токен користувача-адміністратора теж ні: sub — не rozklad-core
        TestAccounts.Created admin = accounts.admin();
        String user = OidcClient.signIn(browsers.open(), admin.username(), TestAccounts.PASSWORD).accessToken();
        assertThat(browser.get(path, "Authorization", "Bearer " + user).statusCode()).isEqualTo(403);
    }

    @Test
    void coreFindsAccountsByUsernameEmailAndIds() {
        TestAccounts.Created first = accounts.teacher();
        TestAccounts.Created second = accounts.admin();
        String bearer = "Bearer " + token("rozklad-core", AuthIntegrationTest.CORE_SECRET, "accounts.manage");
        Browser browser = browsers.open();

        JsonNode byUsername = OidcClient.json(browser.get("/auth/internal/accounts?username="
                + first.username().toUpperCase(), "Authorization", bearer).body());
        JsonNode byEmail = OidcClient.json(browser.get("/auth/internal/accounts?email=" + second.email(),
                "Authorization", bearer).body());
        JsonNode byIds = OidcClient.json(browser.get("/auth/internal/accounts?ids=" + first.id() + "," + second.id(),
                "Authorization", bearer).body());
        JsonNode admins = OidcClient.json(browser.get("/auth/internal/accounts?role=ADMIN&q=" + second.username(),
                "Authorization", bearer).body());

        assertThat(byUsername.get(0).path("id").asString()).isEqualTo(first.id().toString());
        assertThat(byEmail.get(0).path("id").asString()).isEqualTo(second.id().toString());
        assertThat(byIds.valueStream().map(n -> n.path("id").asString()).toList())
                .containsExactlyInAnyOrder(first.id().toString(), second.id().toString());
        assertThat(admins.valueStream().map(n -> n.path("id").asString()).toList()).containsExactly(second.id().toString());
        assertThat(browser.get("/auth/internal/accounts/00000000-0000-4000-8000-000000000000", "Authorization", bearer)
                .statusCode()).isEqualTo(404);
    }

    private String token(String clientId, String secret, String scope) {
        HttpResponse<String> response = tokenResponse(clientId, secret, scope);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return OidcClient.json(response.body()).path("access_token").asString();
    }

    private HttpResponse<String> tokenResponse(String clientId, String secret, String scope) {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        Map<String, String> form = scope == null ? Map.of("grant_type", "client_credentials")
                : Map.of("grant_type", "client_credentials", "scope", scope);
        return browsers.open().postForm("/auth/oauth2/token", form, "Authorization", "Basic " + basic);
    }
}
