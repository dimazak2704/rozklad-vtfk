package ua.vtc.rozklad.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

class ClientRegistrationsTest {

    @Test
    void theSpaIsAPublicPkceClientWithExactUrisForEveryOrigin() {
        RegisteredClient web = find(ClientRegistrations.build(PropertiesFixture.dev(), s -> s), "rozklad-web");

        assertThat(web.getId()).isEqualTo("rozklad-web");
        assertThat(web.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(web.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(web.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(web.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(web.getRedirectUris()).containsExactlyInAnyOrder(
                "http://localhost:5180/login/callback", "http://localhost:5180/login/silent.html",
                "http://localhost:5181/login/callback", "http://localhost:5181/login/silent.html",
                "http://localhost:8088/login/callback", "http://localhost:8088/login/silent.html");
        assertThat(web.getPostLogoutRedirectUris()).containsExactlyInAnyOrder(
                "http://localhost:5180/", "http://localhost:5181/", "http://localhost:8088/");
        assertThat(web.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(web.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(15));
        assertThat(web.getTokenSettings().getAuthorizationCodeTimeToLive()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void theCliClientExistsOnlyWhenEnabled() {
        assertThat(ids(ClientRegistrations.build(PropertiesFixture.dev(), s -> s))).doesNotContain("rozklad-cli");

        RegisteredClient cli = find(ClientRegistrations.build(PropertiesFixture.builder().cliEnabled(true).build(),
                s -> s), "rozklad-cli");
        assertThat(cli.getRedirectUris()).containsExactly("http://127.0.0.1/cli-callback");
        assertThat(cli.getScopes()).containsExactly("openid");
    }

    @Test
    void serviceClientsWithASecretAreConfidentialAndEncoded() {
        List<RegisteredClient> clients = ClientRegistrations.build(PropertiesFixture.dev(), s -> "{enc}" + s);
        RegisteredClient core = find(clients, "rozklad-core");

        assertThat(core.getClientSecret()).isEqualTo("{enc}dev-core-auth-secret");
        assertThat(core.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(core.getClientAuthenticationMethods()).contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(core.getScopes()).containsExactly("accounts.manage");
        assertThat(core.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(5));
        // імпортер без секрету не реєструється
        assertThat(ids(clients)).doesNotContain("rozklad-importer");
    }

    private static RegisteredClient find(List<RegisteredClient> clients, String id) {
        return clients.stream().filter(c -> c.getClientId().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> ids(List<RegisteredClient> clients) {
        return clients.stream().map(RegisteredClient::getClientId).toList();
    }
}
